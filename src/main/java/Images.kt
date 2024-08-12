import org.slf4j.Logger
import org.slf4j.LoggerFactory
import qupath.lib.gui.commands.ProjectCommands
import qupath.lib.images.ImageData
import qupath.lib.images.servers.ImageServerProvider
import qupath.lib.projects.Project
import java.awt.image.BufferedImage
import java.io.File

class Images {
  companion object {
    val logger: Logger = LoggerFactory.getLogger(Images::class.java)
  }
}

fun Project<BufferedImage>.addImages(inputImages: List<InputImage>) {
  val logger = Images.logger

  inputImages
    .mapNotNull { it.localPath }
    .map { File(it) }
    .forEach { file ->
      val imagePath = file.getCanonicalPath()
      logger.debug("Considering: $imagePath")

      val support = ImageServerProvider.getPreferredUriImageSupport(BufferedImage::class.java, imagePath)
      val builder = support.builders[0]

      if (builder == null) {
        logger.info("No builder found for $imagePath; skipping")
        return@forEach
      }

      logger.info("Adding ${file.name} using builder: ${builder.javaClass.simpleName}")

      val entry = this.addImage(builder)

      val imageData = entry.readImageData()
      imageData.imageType = ImageData.ImageType.FLUORESCENCE
      entry.saveImageData(imageData)

      val img = ProjectCommands.getThumbnailRGB(imageData.server)
      entry.thumbnail = img

      entry.imageName = file.name
    }

  this.syncChanges()
}
