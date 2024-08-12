import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.groupChoice
import com.github.ajalt.clikt.parameters.groups.required
import com.github.ajalt.clikt.parameters.options.option
import org.slf4j.LoggerFactory
import qupath.lib.analysis.features.ObjectMeasurements
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

    project.addImages(inputImages)

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

        val pathObjects = extractPathObjects(wholeCellMask1.canonicalPath, downsample, plane)

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
