import org.slf4j.LoggerFactory
import qupath.lib.images.servers.ImageServer
import qupath.lib.regions.RegionRequest
import java.awt.image.BufferedImage

class PrefetchedImageServer(val wrappedImageServer: ImageServer<BufferedImage>) : ImageServer<BufferedImage> by wrappedImageServer {
  private val logger = LoggerFactory.getLogger(PrefetchedImageServer::class.java)

  private var prefetchedImage: BufferedImage? = null

  private fun readFullImage() {
    if (prefetchedImage != null)
      return

    logger.info("Prefetching full image at path: ${wrappedImageServer.path}")

    val wholeImageRequest = RegionRequest.createInstance(
      wrappedImageServer.path,
      1.0,
      0,
      0,
      wrappedImageServer.width,
      wrappedImageServer.height
    )
    prefetchedImage = wrappedImageServer.readRegion(wholeImageRequest)
  }

  override fun readRegion(request: RegionRequest?): BufferedImage {
    if (request?.z != 0 || request.t != 0)
      throw IllegalArgumentException("PrefetchedImageServer only supports z=0 and t=0")

    readFullImage()
    return prefetchedImage!!.getSubimage(request!!.x, request.y, request.width, request.height)
  }

}
