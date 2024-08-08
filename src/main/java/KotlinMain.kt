import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.groupChoice
import com.github.ajalt.clikt.parameters.groups.required
import com.github.ajalt.clikt.parameters.options.option
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
import kotlin.math.roundToInt

fun main(args: Array<String>) {
  println("Initializing QuPath project")

  // This makes sure the scripting.QP import doesn't get "optimized" away
  // We need the import so that various static initializers are run.
  QP()

  InitializeProject().main(args)
}

class InitializeProject : CliktCommand() {
  private val args: Invocation by option("--mode").groupChoice(
    "workspace" to WorkspaceLocation(),
    "explicit" to ExplicitLocations()
  ).required()

  private val imageFilter: String? by option("--image-filter", help = "Filter for image names (file base name)")

  override fun run() {
    val downsample = 1.0
    val plane = ImagePlane.getDefaultPlane()

    val directory = File(args.projectPath)
    if (!directory.exists()) {
      println("No project directory, creating one!")
      directory.mkdirs()
    }

    val project = Projects.createProject(directory, BufferedImage::class.java)

    var inputImages = getImageInputs(args.imagesPath, imageFilter = imageFilter, extension = ".tiff")

    inputImages = fetchRemoteImages(inputImages)

    println("Detected ${inputImages.size} input images: $inputImages")

    println("---")

    for (file in inputImages.mapNotNull { if (it.localPath == null) null else File(it.localPath) }) {
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

    val directoryOfMasks = File(args.segMasksPath)
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
        println("Computing intensity measurements: ${measurements}")

        val updateAfterCount = when {
          numDetectionObjects == 1 -> 1
          numDetectionObjects < 500 -> numDetectionObjects / 5
          numDetectionObjects < 50000 -> numDetectionObjects / 50
          else -> numDetectionObjects / 100
        }

        for ((processed, detection) in imageData.hierarchy.detectionObjects.withIndex()) {
          if (processed % updateAfterCount == 0) {
            println("${(100 * processed.toFloat() / numDetectionObjects).roundToInt()}% complete")
          }
          ObjectMeasurements.addIntensityMeasurements(server, detection, downsample, measurements, listOf())
          ObjectMeasurements.addShapeMeasurements(
            detection, server.pixelCalibration,
            *ObjectMeasurements.ShapeFeatures.entries.toTypedArray()
          )
        }
        println("100% complete: ${imgName}")
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
