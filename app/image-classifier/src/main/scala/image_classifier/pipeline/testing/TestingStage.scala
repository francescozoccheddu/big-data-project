package image_classifier.pipeline.testing

import image_classifier.configuration.TestingConfig
import image_classifier.pipeline.Stage
import image_classifier.pipeline.training.TrainingStage
import image_classifier.utils.FileUtils
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types.DataType

private[pipeline] final class TestingStage(config: Option[TestingConfig], val trainingStage: TrainingStage)(implicit spark: SparkSession, fileUtils: FileUtils)
  extends Stage[Unit, TestingConfig]("Testing", config) {

	private def validate(schema: DataType) = {
		import org.apache.spark.ml.linalg.SQLDataTypes.VectorType
		import image_classifier.utils.DataTypeImplicits.DataTypeExtension
		import org.apache.spark.sql.types.{IntegerType, BooleanType}
		val featurizationStage = trainingStage.featurizationStage
		schema.requireField(featurizationStage.outputCol, VectorType)
		schema.requireField(featurizationStage.dataStage.isTestCol, BooleanType)
		schema.requireField(featurizationStage.dataStage.labelCol, IntegerType)
	}

	override protected def run(specs: TestingConfig): Unit = {
		import org.apache.spark.mllib.evaluation.MulticlassMetrics
		import image_classifier.pipeline.testing.TestingStage.logger
		import org.apache.spark.sql.functions.col
		import org.apache.spark.sql.types.DoubleType
		import image_classifier.utils.OptionImplicits._
		val model = trainingStage.result
		val featurizationStage = trainingStage.featurizationStage
		validate(featurizationStage.result.schema)
		val dataStage = featurizationStage.dataStage
		val test = featurizationStage.result.filter(col(dataStage.isTestCol))
		val data = model
			.transform(test)
			.select(col(trainingStage.predictionCol), col(dataStage.labelCol).cast(DoubleType))
			.rdd
			.map(r => (r.getDouble(0), r.getDouble(1)))
		val metrics = new MulticlassMetrics(data)
		val labels = specs.labels.getOr(() => metrics.labels.map(_.toInt.toString))
		require(labels.length == metrics.labels.length)
		val summary = TestingStage.print(metrics, labels)
		if (specs.save.isDefined) {
			logger.info(s"Writing metrics to '${specs.save.get}'")
			fileUtils.writeString(specs.save.get, summary)
		} else {
			logger.info(s"Writing metrics to stdout")
			println()
			println(summary)
		}
	}

}

private[testing] object TestingStage {
	import org.apache.log4j.Logger
	import org.apache.spark.mllib.evaluation.MulticlassMetrics

	import java.io.PrintStream

	private final class Printer {

		private val minSpace = 32
		private val builder = new StringBuilder(4096)

		private def truncate(value: Double)
		= "%.3f".format(value)

		def addPercent(key: String, value: Double): Unit =
			add(key, truncate(value * 100) + "%")

		private def addAligned(value: String, column: Int): Unit = {
			val space = minSpace max column
			val body = value
				.lines
				.map(" " * space + _.trim)
				.mkString("\n")
				.substring(column)
			builder.append(body)
		}

		def add(key: String, value: String): Unit = {
			val header = s"$key:  "
			builder.append(header)
			addAligned(value, header.length)
			builder.append('\n')
		}

		def addSection(key: String) = {
			if (builder.nonEmpty)
				builder.append('\n')
			builder.append(s"--- $key ---")
		}

		def get = builder.toString

	}

	private val logger = Logger.getLogger(getClass)

	private def print(metrics: MulticlassMetrics, labels: Seq[String]) = {
		val printer = new Printer
		printer.addSection("Summary")
		printer.addPercent("Accuracy", metrics.accuracy)
		printer.addPercent("Hamming loss", metrics.hammingLoss)
		printer.addPercent("F-Measure", metrics.weightedFMeasure)
		printer.addPercent("Precision", metrics.weightedPrecision)
		printer.addPercent("Recall", metrics.weightedRecall)
		printer.addPercent("True positives", metrics.weightedTruePositiveRate)
		printer.addPercent("False positives", metrics.weightedFalsePositiveRate)
		printer.add("Confusion matrix", metrics.confusionMatrix.toString)
		for ((l, i) <- labels.zipWithIndex) {
			printer.addSection(s"Class '$l'")
			printer.addPercent("F-Measure", metrics.fMeasure(i))
			printer.addPercent("Precision", metrics.precision(i))
			printer.addPercent("Recall", metrics.recall(i))
			printer.addPercent("True positives", metrics.truePositiveRate(i))
			printer.addPercent("False positives", metrics.falsePositiveRate(i))
		}
		printer.get
	}

}
