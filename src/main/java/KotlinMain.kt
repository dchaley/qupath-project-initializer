import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.groupChoice
import com.github.ajalt.clikt.parameters.groups.required
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import ij.IJ
import ij.process.ColorProcessor
import qupath.imagej.processing.RoiLabeling
import qupath.imagej.tools.IJTools
import qupath.lib.analysis.features.ObjectMeasurements
import qupath.lib.gui.commands.ProjectCommands
import qupath.lib.images.ImageData
import qupath.lib.images.servers.ImageServerProvider
import qupath.lib.objects.PathObjects
import qupath.lib.projects.Projects
import qupath.lib.regions.ImagePlane
import qupath.lib.scripting.QP
import qupath.lib.scripting.QP.fireHierarchyUpdate
import qupath.lib.scripting.QP.resolveHierarchy
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

fun getFiles(dir: File, extension: String, filter: String = ""): List<File> {
  val files = mutableListOf<File>()
  dir.walk().forEach {
    if (it.isFile && it.name.lowercase().contains(filter) && it.name.lowercase().endsWith(extension)) {
      files.add(it)
    }
  }
  return files
}

sealed class InvocationStyle(name: String) : OptionGroup(name) {
  abstract val imagesPath: String
  abstract val segMasksPath: String
  abstract val projectPath: String
}

class WorkspaceLocation : InvocationStyle("workspace") {
  private val workspacePath: String by option("--workspace-path", help = "Root directory of the workspace").required()
  private val imagesSubdir: String by option("--images-subdir", help = "Name of the folder containing OME-TIFF images").default("OMETIFF")
  private val segMasksSubdir: String by option("--segmasks-subdir", help = "Name of the folder containing segmentation masks").default("SEGMASKS")
  private val projectSubdir: String by option("--project-subdir", help = "Name of the folder to save QuPath project").default("QUPATH")

  override val imagesPath: String
    get() = "$workspacePath/$imagesSubdir"
  override val segMasksPath: String
    get() = "$workspacePath/$segMasksSubdir"
  override val projectPath: String
    get() = "$workspacePath/$projectSubdir"
}

class ExplicitLocations : InvocationStyle("explicit") {
  override val imagesPath: String by option("--images-path", help = "Directory containing OME-TIFF images").required()
  override val segMasksPath: String by option("--segmasks-path", help = "Directory containing segmentation masks").required()
  override val projectPath: String by option("--project-path", help = "Directory to save QuPath project").required()
  val outputPath: String? by option(help = "Output path for QuPath measurements")
}

fun main(args: Array<String>) {
  println("Initializing QuPath project")

  // This makes sure the scripting.QP import doesn't get "optimized" away
  // We need the import so that various static initializers are run.
  QP()

  val regionSet: String? = null
  InitializeProject().main(args)
}

class InitializeProject : CliktCommand() {
  private val invocationStyle by option("--mode").groupChoice(
    "workspace" to WorkspaceLocation(),
    "explicit" to ExplicitLocations()
  ).required()

  override fun run() {
    val omeDir = invocationStyle.imagesPath
    val maskDir = invocationStyle.segMasksPath
    val prjtDir = invocationStyle.projectPath

    val downsample = 1.0
    val plane = ImagePlane.getDefaultPlane()

    val directory = File(prjtDir)
    if (!directory.exists()) {
      println("No project directory, creating one!")
      directory.mkdirs()
    }

    val project = Projects.createProject(directory, BufferedImage::class.java)

    val files = getFiles(File(omeDir), extension = ".tiff")

    println("---")

    for (file in files) {
      val imagePath = file.getCanonicalPath()
      println(imagePath)

      val support = ImageServerProvider.getPreferredUriImageSupport(BufferedImage::class.java, imagePath)
      val builder = support.builders[0]

      if (builder == null) {
        println("No builder found for $imagePath")
        continue
      }

      println("Adding: $imagePath")

      val entry = project.addImage(builder)

      val imageData = entry.readImageData()
      imageData.imageType = ImageData.ImageType.FLUORESCENCE
      entry.saveImageData(imageData)

      val img = ProjectCommands.getThumbnailRGB(imageData.server)
      entry.thumbnail = img

      entry.imageName = file.name
    }

    project.syncChanges()

    val directoryOfMasks = File(maskDir)
    if (directoryOfMasks.exists()) {
      println("Discovering mask files...")
      val wholeCellFiles = mutableListOf<File>()

      directoryOfMasks.walk().forEach {
        if (it.isFile && it.name.endsWith("_WholeCellMask.tiff")) {
          wholeCellFiles.add(it)
        }
      }

      project.imageList.forEach() { entry ->
        val imgName = entry.imageName

        val sample = imgName.substringAfterLast(":").substringBefore(".")
        println(" >>> $sample")
        val imageData = entry.readImageData()
        val server = imageData.server

        val wholeCellMask1 = wholeCellFiles.find { it.name.contains("${sample}_") }
        if (wholeCellMask1 == null) {
          println(" >>> MISSING MASK FILES!! <<<")
          println()
          return@forEach
        }

        val imp = IJ.openImage(wholeCellMask1.absolutePath)
        print(imp)
        val n = imp.statistics.max.toInt()
        println("   Max Cell Label: $n")
        if (n == 0) {
          println(" >>> No objects found! <<<")
          return
        }

        val ip = imp.processor
        if (ip is ColorProcessor) {
          throw IllegalArgumentException("RGB images are not supported!")
        }

        val roisIJ = RoiLabeling.labelsToConnectedROIs(ip, n)
        val rois = roisIJ.mapNotNull {
          if (it == null) {
            null
          } else {
            IJTools.convertToROI(it, 0.0, 0.0, downsample, plane)
          }
        }

        val pathObjects = rois.map { PathObjects.createDetectionObject(it) }

        println("  Number of Pathobjects: ${pathObjects.size}")
        imageData.hierarchy.addObjects(pathObjects)
        resolveHierarchy()
        entry.saveImageData(imageData)

        println(" >>> Calculating measurements...")
        println(imageData.hierarchy)
        val numDetectionObjects = imageData.hierarchy.detectionObjects.size
        println("  DetectionObjects: $numDetectionObjects")
        val measurements = ObjectMeasurements.Measurements.entries
        println(measurements)
        for ((processed, detection) in imageData.hierarchy.detectionObjects.withIndex()) {
          // Use 1 at minimum to avoid division by zero when num objects < 50
          if (processed % max((numDetectionObjects / 50), 1) == 0) {
            println("${(100 * processed.toFloat() / numDetectionObjects).roundToInt()}% complete")
          }
          ObjectMeasurements.addIntensityMeasurements(server, detection, downsample, measurements, listOf())
          ObjectMeasurements.addShapeMeasurements(
            detection, server.pixelCalibration,
            *ObjectMeasurements.ShapeFeatures.entries.toTypedArray()
          )
        }
        fireHierarchyUpdate()
        entry.saveImageData(imageData)
        imageData.server.close()
      }
    }

    project.syncChanges()
    println("")
    println("Done.")
  }
}
