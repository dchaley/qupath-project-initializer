import Main.Companion.logger
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.groupChoice
import com.github.ajalt.clikt.parameters.groups.required
import com.github.ajalt.clikt.parameters.options.option
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import qupath.lib.analysis.features.ObjectMeasurements
import qupath.lib.projects.ProjectImageEntry
import qupath.lib.projects.Projects
import qupath.lib.regions.ImagePlane
import qupath.lib.scripting.QP
import qupath.lib.scripting.QP.fireHierarchyUpdate
import qupath.lib.scripting.QP.resolveHierarchy
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.roundToInt

class Main {
  companion object {
    val logger: Logger = LoggerFactory.getLogger(Main::class.java)
  }
}

fun main(args: Array<String>) {
  logger.info("Initializing QuPath project")

  // This makes sure the scripting.QP import doesn't get "optimized" away
  // We need the import so that various static initializers are run.
  QP()

  InitializeProject().main(args)
}

fun addImageObjects(
  entry: ProjectImageEntry<BufferedImage>,
  nucleusMaskFiles: List<File>,
  wholeCellFiles: List<File>,
  downsample: Double,
  plane: ImagePlane,
) {
  logger.info("Adding image objects")
  val imgName = entry.imageName

  val sample = imgName.substringAfterLast(":").substringBefore(".")
  logger.info(" >>> $sample")
  val imageData = entry.readImageData()
  val server = imageData.server

  val nucleusMaskFile = nucleusMaskFiles.find { it.name.contains("${sample}_") }
  val wholeCellMaskFile = wholeCellFiles.find { it.name.contains("${sample}_") }

  if (wholeCellMaskFile == null) {
    logger.warn(" >>> MISSING WHOLE CELL MASK FILE!! For: $sample.")
    return
  }

  val pathObjects = if (nucleusMaskFile == null) {
    extractWholeCellObjects(
      wholeCellMaskFile.canonicalPath, downsample, plane,
    )
  } else {
    extractCellObjects(
      nucleusMaskFile.canonicalPath, wholeCellMaskFile.canonicalPath, downsample, plane,
    )
  }

  logger.info("  Number of PathObjects: ${pathObjects.size}")
  imageData.hierarchy.addObjects(pathObjects)
  resolveHierarchy()
  entry.saveImageData(imageData)

  logger.info(" >>> Calculating measurements...")
  logger.info(imageData.hierarchy.toString())
  val numDetectionObjects = imageData.hierarchy.detectionObjects.size
  logger.info("  DetectionObjects: $numDetectionObjects")
  val measurements = ObjectMeasurements.Measurements.entries
  logger.info("Computing intensity measurements: ${measurements}")
  val compartments = ObjectMeasurements.Compartments.entries

  val updateAfterCount = when {
    numDetectionObjects == 1 -> 1
    numDetectionObjects < 500 -> numDetectionObjects / 5
    numDetectionObjects < 50000 -> numDetectionObjects / 50
    else -> numDetectionObjects / 100
  }

  val prefetchedImageServer = PrefetchedImageServer(server)

  for ((processed, detection) in imageData.hierarchy.detectionObjects.withIndex()) {
    if (processed % updateAfterCount == 0) {
      logger.info("${(100 * processed.toFloat() / numDetectionObjects).roundToInt()}% complete")
    }
    ObjectMeasurements.addIntensityMeasurements(
      prefetchedImageServer,
      detection,
      downsample,
      measurements,
      compartments,
    )
    ObjectMeasurements.addShapeMeasurements(
      detection, prefetchedImageServer.pixelCalibration,
      *ObjectMeasurements.ShapeFeatures.entries.toTypedArray()
    )
  }
  logger.info("100% complete: ${imgName}")
  fireHierarchyUpdate()
  entry.saveImageData(imageData)
  imageData.server.close()
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

    val projectDirectory = prepWorkingDirectory(args.projectPath)

    val project = Projects.createProject(projectDirectory, BufferedImage::class.java)

    logger.info("Discovering input files...")
    var inputImages = getImageInputs(args.imagesPath, imageFilter = imageFilter, extension = ".tiff")
    logger.info("Fetching remote image files...")
    inputImages = fetchRemoteImages(inputImages)

    logger.info("Adding ${inputImages.size} input images: $inputImages")
    project.addImages(inputImages)

    logger.info("Discovering mask files...")
    var nucleusMaskInputs = getImageInputs(args.segMasksPath, extension = "_NucleusMask.tiff")
    // Pass in an empty list of nucleus files to use only whole cell masks.
    // This is because nucleus matching doesn't work yet when the number
    // of whole-cells is different from the number of nuclei in the masks.
    var wholeCellInputs = getImageInputs(args.segMasksPath, extension = "_WholeCellMask.tiff")
    logger.info("Fetching remote mask files...")
    nucleusMaskInputs = fetchRemoteImages(nucleusMaskInputs)
    wholeCellInputs = fetchRemoteImages(wholeCellInputs)

    val nucleusMaskFiles = nucleusMaskInputs.mapNotNull { it.localPath }.map { File(it) }
    val wholeCellFiles = wholeCellInputs.mapNotNull { it.localPath }.map { File(it) }

    if (wholeCellFiles.isNotEmpty()) {
      project.imageList.forEach() { entry ->
        addImageObjects(entry, nucleusMaskFiles, wholeCellFiles, downsample, plane)
      }
    }

    project.syncChanges()

    logger.info("Outputting reports to: ${args.reportsPath}")
    val reportsWorkdir = prepWorkingDirectory(args.reportsPath)
    outputReports(reportsWorkdir, project)
    uploadToRemote(reportsWorkdir, args.reportsPath)

    uploadToRemote(projectDirectory, args.projectPath)
    logger.info("Done.")
  }
}
