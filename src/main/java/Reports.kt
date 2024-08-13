import Reports.Companion.logger
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import qupath.lib.gui.tools.MeasurementExporter
import qupath.lib.objects.PathDetectionObject
import qupath.lib.projects.Project
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Paths

class Reports {
  companion object {
    val logger: Logger = LoggerFactory.getLogger(Reports::class.java)
  }
}

fun outputReports(outputRoot: File, project: Project<BufferedImage>) {
  project.imageList.forEach {
    val imgName = it.imageName.substringBefore(".")
    val separator = "\t"
    val exportType = PathDetectionObject::class.java
    val outputFile = Paths.get(outputRoot.canonicalPath, "${imgName}_QUANT.tsv").toFile()

    logger.info("Exporting measurements for $imgName to: ${outputFile.canonicalPath}")

    MeasurementExporter()
      .imageList(listOf(it)) // Images from which measurements will be exported
      .separator(separator) // Character that separates values
      .exportType(exportType) // Type of objects to export
      .exportMeasurements(outputFile) // Start the export process
  }

  logger.info("Done exporting measurements.")
}
