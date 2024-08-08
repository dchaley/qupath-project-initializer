import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import com.google.cloud.storage.transfermanager.ParallelDownloadConfig
import com.google.cloud.storage.transfermanager.TransferManagerConfig
import com.google.cloud.storage.transfermanager.TransferStatus
import org.apache.commons.io.FileUtils
import java.io.File
import java.net.URI
import java.nio.file.Files
import kotlin.io.path.Path


data class InputImage(val imageName: String, val uri: URI, val localPath: String? = null)

fun listGsInputs(uri: String, filter: String?, extension: String): List<InputImage> {
  val storage: Storage = StorageOptions.getDefaultInstance().getService()
  val rootBlobId = BlobId.fromGsUtilUri(uri)

  // Return all objects that:
  // - aren't directories
  // - contain the filter string
  // - end with the specified extension
  return storage.list(rootBlobId.bucket, Storage.BlobListOption.prefix(rootBlobId.name)).iterateAll()
    .mapNotNull { blob ->
      if (blob.name.endsWith("/")
        || !blob.name.contains(filter.orEmpty(), ignoreCase = true)
        || !blob.name.endsWith(extension, ignoreCase = true)
      ) {
        return@mapNotNull null
      }

      // The blob name is the entire key including parent folders. Keep only the filename.
      val fileName = Path(blob.name).fileName.toString()
      InputImage(fileName, URI.create("gs://${blob.bucket}/${blob.name}"))
    }
}

fun listFileInputs(dirPath: String, extension: String, filter: String?): List<InputImage> {
  return File(dirPath).walk().mapNotNull {
    if (!it.isFile
      || !it.name.contains(filter.orEmpty(), ignoreCase = true)
      || !it.name.endsWith(extension, ignoreCase = true)
    ) {
      return@mapNotNull null
    }

    InputImage(it.name, it.toURI(), it.canonicalPath)
  }.toList()
}

fun getImageInputs(imagesPath: String, extension: String, imageFilter: String? = null): List<InputImage> {
  val imagesRootUri = URI.create(imagesPath)

  return if (imagesRootUri.scheme == "gs") {
    listGsInputs(imagesPath, filter = imageFilter, extension = extension)
  } else {
    listFileInputs(imagesPath, filter = imageFilter, extension = extension)
  }
}

fun fetchRemoteImages(inputImages: List<InputImage>): List<InputImage> {
  // Create a location to store remote images.
  val localRoot = Files.createTempDirectory("images").toFile()
  // Delete it on process exit.
  Runtime.getRuntime().addShutdownHook(Thread { FileUtils.forceDelete(localRoot) })

  // Collect all remote paths that need downloading
  val remoteBlobs = inputImages.filter { it.localPath == null }.map { BlobId.fromGsUtilUri(it.uri.toString()) }

  // No remotes? Nothing to do.
  if (remoteBlobs.isEmpty()) {
    return inputImages
  }

  // We only support 1 remote bucket for now. Assert we only have one (or none).
  remoteBlobs.map { it.bucket }.distinct().let {
    require(it.size <= 1) { "All remote images must be in the same bucket, found: $it buckets" }
  }

  // The remote root is parent path of all remote blobs.
  val remoteRoot = remoteBlobs.first().let {
    BlobId.of(it.bucket, it.name.substring(0, it.name.lastIndexOf('/') + 1))
  }

  val transferManager = TransferManagerConfig.newBuilder()
    .setAllowDivideAndConquerDownload(true).build().service

  // .use auto-closes the transfer manager.
  val results = transferManager.use {
    val parallelDownloadConfig: ParallelDownloadConfig? =
      ParallelDownloadConfig.newBuilder()
        .setBucketName(remoteRoot.bucket)
        .setStripPrefix(remoteRoot.name)
        .setDownloadDirectory(localRoot.toPath())
        .build()

    it.downloadBlobs(
      remoteBlobs.map { b -> BlobInfo.newBuilder(b).build() },
      parallelDownloadConfig
    ).downloadResults
  }

  results.forEach {
    require(it.status == TransferStatus.SUCCESS) { "Failed to download ${it.input.name}: ${it.exception}" }
  }

  return inputImages.map { inputImage ->
    // Skip inputs that already have local paths
    if (inputImage.localPath != null) {
      return@map inputImage
    }

    val localPath = File(localRoot, inputImage.imageName)
    localPath.exists() || error("Couldn't find downloaded remote image ${inputImage.uri}, expected at: ${localPath.canonicalPath}")

    // Replace the input w/o local path to one with the newly downloaded path.
    inputImage.copy(localPath = localPath.canonicalPath)
  }
}
