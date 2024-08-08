import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.groupChoice
import com.github.ajalt.clikt.parameters.groups.required
import com.github.ajalt.clikt.parameters.options.option
import ij.IJ
import ij.process.ColorProcessor
import org.slf4j.LoggerFactory
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
  LoggerFactory.getLogger(InitializeProject::class.java).info("Initializing QuPath project")

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

  private val logger = LoggerFactory.getLogger(InitializeProject::class.java)

  override fun run() {
    val downsample = 1.0
    val plane = ImagePlane.getDefaultPlane()

    val directory = File(args.projectPath)
    if (!directory.exists()) {
      logger.info("No project directory, creating one!")
      directory.mkdirs()
    }

    val project = Projects.createProject(directory, BufferedImage::class.java)

    var inputImages = getImageInputs(args.imagesPath, imageFilter = imageFilter, extension = ".tiff")

    inputImages = fetchRemoteImages(inputImages)

    logger.info("Detected ${inputImages.size} input images: $inputImages")

    inputImages.mapNotNull { input -> input.localPath?.let { File(it) } }.forEach { file ->
      val imagePath = file.getCanonicalPath()
      logger.debug("Considering: $imagePath")

      val support = ImageServerProvider.getPreferredUriImageSupport(BufferedImage::class.java, imagePath)
      val builder = support.builders[0]

      if (builder == null) {
        logger.info("No builder found for $imagePath; skipping")
        return@forEach
      }

      logger.info("Adding ${file.name} using builder: ${builder.javaClass.simpleName}")

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
      logger.info("Discovering mask files...")
      val wholeCellFiles = mutableListOf<File>()

      directoryOfMasks.walk().forEach {
        if (it.isFile && it.name.endsWith("_WholeCellMask.tiff")) {
          wholeCellFiles.add(it)
        }
      }

      project.imageList.forEach() { entry ->
        val imgName = entry.imageName

        val sample = imgName.substringAfterLast(":").substringBefore(".")
        logger.info(" >>> $sample")
        val imageData = entry.readImageData()
        val server = imageData.server

        val wholeCellMask1 = wholeCellFiles.find { it.name.contains("${sample}_") }
        if (wholeCellMask1 == null) {
          logger.warn(" >>> MISSING MASK FILES!! For: $sample <<<")
          return@forEach
        }

        val imp = IJ.openImage(wholeCellMask1.absolutePath)
        logger.info(imp.toString())
        val n = imp.statistics.max.toInt()
        logger.info("   Max Cell Label: $n")
        if (n == 0) {
          logger.info(" >>> No objects found! <<<")
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

        logger.info("  Number of Pathobjects: ${pathObjects.size}")
        imageData.hierarchy.addObjects(pathObjects)
        resolveHierarchy()
        entry.saveImageData(imageData)

        logger.info(" >>> Calculating measurements...")
        logger.info(imageData.hierarchy.toString())
        val numDetectionObjects = imageData.hierarchy.detectionObjects.size
        logger.info("  DetectionObjects: $numDetectionObjects")
        val measurements = ObjectMeasurements.Measurements.entries
        logger.info("Computing intensity measurements: ${measurements}")

        val updateAfterCount = when {
          numDetectionObjects == 1 -> 1
          numDetectionObjects < 500 -> numDetectionObjects / 5
          numDetectionObjects < 50000 -> numDetectionObjects / 50
          else -> numDetectionObjects / 100
        }

        for ((processed, detection) in imageData.hierarchy.detectionObjects.withIndex()) {
          if (processed % updateAfterCount == 0) {
            logger.info("${(100 * processed.toFloat() / numDetectionObjects).roundToInt()}% complete")
          }
          ObjectMeasurements.addIntensityMeasurements(server, detection, downsample, measurements, listOf())
          ObjectMeasurements.addShapeMeasurements(
            detection, server.pixelCalibration,
            *ObjectMeasurements.ShapeFeatures.entries.toTypedArray()
          )
        }
        logger.info("100% complete: ${imgName}")
        fireHierarchyUpdate()
        entry.saveImageData(imageData)
        imageData.server.close()
      }
    }

    project.syncChanges()
    logger.info("Done.")
  }
}
