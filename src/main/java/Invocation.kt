import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required

sealed class Invocation(name: String) : OptionGroup(name) {
  abstract val imagesPath: String
  abstract val segMasksPath: String
  abstract val projectPath: String
  abstract val reportsPath: String
}

class WorkspaceLocation : Invocation("workspace") {
  private val workspacePath: String by option(
    "--workspace-path", help = "Root directory of the workspace",
  ).required()
  private val imagesSubdir: String by option(
    "--images-subdir", help = "Name of the folder containing OME-TIFF images",
  ).default("OMETIFF")
  private val segMasksSubdir: String by option(
    "--segmasks-subdir", help = "Name of the folder containing segmentation masks",
  ).default("SEGMASKS")
  private val projectSubdir: String by option(
    "--project-subdir", help = "Name of the folder to save QuPath project",
  ).default("QUPATH")
  private val reportSubdir: String by option(
    "--reports-subdir", help = "Name of the folder for QuPath measurements",
  ).default("REPORTS")

  override val imagesPath: String
    get() = "$workspacePath/$imagesSubdir"
  override val segMasksPath: String
    get() = "$workspacePath/$segMasksSubdir"
  override val projectPath: String
    get() = "$workspacePath/$projectSubdir"
  override val reportsPath: String
    get() = "$workspacePath/$reportSubdir"
}

class ExplicitLocations : Invocation("explicit") {
  override val imagesPath: String by option(
    "--images-path", help = "Directory containing OME-TIFF images",
  ).required()
  override val segMasksPath: String by option(
    "--segmasks-path", help = "Directory containing segmentation masks",
  ).required()
  override val projectPath: String by option(
    "--project-path", help = "Directory to save QuPath project",
  ).required()
  override val reportsPath: String by option(
    "--reports-path", help = "Output path for QuPath measurements",
  ).required()
}

