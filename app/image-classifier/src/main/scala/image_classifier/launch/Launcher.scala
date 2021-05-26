package image_classifier.launch

object Launcher {

	import image_classifier.configuration.Config

	def run(configFile: String): Unit = {
		import java.nio.file.Paths
		val configDir = Paths.get(configFile).getParent.toString
		val config = Config.fromFile(configFile)
		run(config, configDir)
	}

	def run(config: Config, workingDir: String): Unit = {
		import image_classifier.utils.SparkInstance
		import image_classifier.pipeline.Pipeline
		SparkInstance.execute(Pipeline.run(config, workingDir)(_))
	}

}
