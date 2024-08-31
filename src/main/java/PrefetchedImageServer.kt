import org.slf4j.LoggerFactory
import qupath.lib.images.servers.*
import qupath.lib.regions.RegionRequest
import java.awt.image.BufferedImage
import java.net.URI

class PrefetchedImageServer(val wrappedImageServer: ImageServer<BufferedImage>) : ImageServer<BufferedImage> {
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

  override fun close() {
    wrappedImageServer.close()
  }

  override fun getPath(): String {
    return wrappedImageServer.path
  }

  override fun getURIs(): MutableCollection<URI> {
    return wrappedImageServer.urIs
  }

  override fun getPreferredDownsamples(): DoubleArray {
    return wrappedImageServer.preferredDownsamples
  }

  override fun nResolutions(): Int {
    return wrappedImageServer.nResolutions()
  }

  override fun getDownsampleForResolution(level: Int): Double {
    return wrappedImageServer.getDownsampleForResolution(level)
  }

  override fun getWidth(): Int {
    return wrappedImageServer.width
  }

  override fun getHeight(): Int {
    return wrappedImageServer.height
  }

  override fun nChannels(): Int {
    return wrappedImageServer.nChannels()
  }

  override fun isRGB(): Boolean {
    return wrappedImageServer.isRGB
  }

  override fun nZSlices(): Int {
    return wrappedImageServer.nZSlices()
  }

  override fun nTimepoints(): Int {
    return wrappedImageServer.nTimepoints()
  }

  override fun getCachedTile(tile: TileRequest?): BufferedImage {
    return wrappedImageServer.getCachedTile(tile)
  }

  override fun getServerType(): String {
    return wrappedImageServer.serverType
  }

  override fun getAssociatedImageList(): MutableList<String> {
    return wrappedImageServer.associatedImageList
  }

  override fun getAssociatedImage(name: String?): BufferedImage {
    return wrappedImageServer.getAssociatedImage(name)
  }

  override fun isEmptyRegion(request: RegionRequest?): Boolean {
    return wrappedImageServer.isEmptyRegion(request)
  }

  override fun getPixelType(): PixelType {
    return wrappedImageServer.pixelType
  }

  override fun getChannel(channel: Int): ImageChannel {
    return wrappedImageServer.getChannel(channel)
  }

  override fun getMetadata(): ImageServerMetadata {
    return wrappedImageServer.metadata
  }

  override fun setMetadata(metadata: ImageServerMetadata?) {
    wrappedImageServer.metadata = metadata
  }

  override fun getOriginalMetadata(): ImageServerMetadata {
    return wrappedImageServer.originalMetadata
  }

  override fun getDefaultThumbnail(z: Int, t: Int): BufferedImage {
    return wrappedImageServer.getDefaultThumbnail(z, t)
  }

  override fun getTileRequestManager(): TileRequestManager {
    return wrappedImageServer.tileRequestManager
  }

  override fun getImageClass(): Class<BufferedImage> {
    return wrappedImageServer.imageClass
  }
}
