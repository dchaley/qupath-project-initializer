import com.google.cloud.storage.BlobId
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import com.google.cloud.storage.transfermanager.ParallelDownloadConfig
import com.google.cloud.storage.transfermanager.TransferManager
import com.google.cloud.storage.transfermanager.TransferManagerConfig
import org.apache.commons.io.FileUtils
import java.io.File
import java.net.URI
import java.nio.file.Files


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

      InputImage(blob.name, URI.create("gs://${blob.bucket}/${blob.name}"))
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

