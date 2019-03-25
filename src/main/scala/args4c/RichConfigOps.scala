package args4c

import java.util.concurrent.TimeUnit

import com.typesafe.config._

import scala.compat.Platform
import scala.concurrent.duration._
import scala.language.{implicitConversions, postfixOps}
import scala.util.Try

/**
  * Provider operations on a 'config'
  */
trait RichConfigOps extends LowPriorityArgs4cImplicits {

  def config: Config

  def defaultRenderOptions = ConfigRenderOptions.concise.setJson(false)

  /**
    *
    * @param password
    * @return the encrypted configuration
    */
  def encrypt(password: Array[Byte]) = Encryption.encryptAES(password, asJson)._2

  import ConfigFactory._
  import RichConfig._

  /** @param key the configuration path
    * @return the value at the given key as a scala duration
    */
  def asDuration(key: String): Duration = {
    config.getString(key).toLowerCase() match {
      case "inf" | "infinite" => Duration.Inf
      case _                  => asFiniteDuration(key)
    }
  }

  def asFiniteDuration(key: String): FiniteDuration = {
    config.getDuration(key, TimeUnit.MILLISECONDS).millis
  }

  /**
    * If 'show=X' is specified, configuration values which contain X in their path will be displayed with the values matching 'obscure' obscured.
    *
    * If 'X' is 'all' or 'root', then the entire configuration is rendered.
    *
    * This can be useful to debug other command-line args (to ensure they take the desired effect)
    * or to validate the environment variable replacement
    *
    * @param obscure a function which takes a dotted configuration path and string value and returns the value to display
    * @return the optional value of what's pointed to if 'show=<path>' is specified
    */
  def showIfSpecified(obscure: (String, String) => String = obscurePassword(_, _)): Option[String] = {
    if (config.hasPath("show")) {
      val filteredConf = config.getString("show") match {
        case "all" | "" | "root" => config
        case path =>
          val lcPath = path.toLowerCase
          config.filter(_.toLowerCase.contains(lcPath))
      }
      Option(filteredConf.summary(obscure))
    } else {
      None
    }
  }

  /** Overlay the given arguments over this configuration, where the arguments are taken to be in the form:
    *
    * $ the path to a configuration file, either on the classpath or file system
    * $ a <key>=<value> pair where the key is a 'path.to.a.configuration.entry'
    *
    * @param args            the user arguments in the form <key>=<value>, <filePath> or <fileOnTheClasspath>
    * @param unrecognizedArg what to do with malformed user input
    * @return a configuration with the given user-argument overrides applied over top
    */
  def withUserArgs(args: Array[String], unrecognizedArg: String => Config = ParseArg.Throw): Config = {
    def isSimpleList(key: String) = {
      def isList = Try(config.getStringList(key)).isSuccess

      config.hasPath(key) && isList
    }

    def isObjectList(key: String) = {
      def isList = Try(config.getObjectList(key)).isSuccess

      config.hasPath(key) && isList
    }

    val configs: Array[Config] = args.map {
      case KeyValue(k, v) if isSimpleList(k) =>
        asConfig(k, java.util.Arrays.asList(v.split(",", -1): _*))
      case KeyValue(k, v) if isObjectList(k) =>
        sys.error(s"Path '$k' tried to override an object list with '$v'")
      case KeyValue(k, v)    => asConfig(k, v)
      case FilePathConfig(c) => c
      case UrlPathConfig(c)  => c
      case other             => unrecognizedArg(other)
    }

    (configs :+ config).reduce(_ withFallback _)
  }

  /**
    * produces a scala list, either from a StringList or a comma-separated string value
    *
    * @param separator if specified, the value at the given path will be parsed if it is a string and not a stringlist
    * @param path      the config path
    */
  def asList(path: String, separator: Option[String] = Option(",")): List[String] = {
    import collection.JavaConverters._
    try {
      config.getStringList(path).asScala.toList
    } catch {
      case e: ConfigException.WrongType =>
        separator.fold(throw e) { sep =>
          config.getString(path).split(sep, -1).toList
        }
    }
  }

  /** And example which uses most of the below stuff to showcase what this is for
    * Note : writing a 'diff' using this would be pretty straight forward
    */
  def uniquePaths: Seq[String] = withoutSystem.paths.sorted

  /** this config w/o the system properties or environment variables */
  def withoutSystem: Config = {
    val sysConf = systemEnvironment.withFallback(systemProperties).withFallback(sysEnvAsConfig())
    without(sysConf)
  }

  def without(other: Config): Config = without(asRichConfig(other).paths)

  def without(firstPath: String, theRest: String*): Config = without(firstPath +: theRest)

  def without(configPaths: TraversableOnce[String]): Config = {
    configPaths.foldLeft(config)(_ withoutPath _)
  }

  def filter(path: String => Boolean): Config = filterNot(path.andThen(_.unary_!))

  def filterNot(path: String => Boolean): Config = without(paths.filter(path))

  /** @return the configuration as a json string
    */
  def asJson: String = config.root.render(ConfigRenderOptions.concise().setJson(true))

  /** @return all the unique paths for this configuration
    */
  def paths: Seq[String] = {
    entries.map(_._1).toSeq.sorted
  }

  /** @return the configuration entries as a set of entries
    */
  def entries: Set[(String, ConfigValue)] = {
    import scala.collection.JavaConverters._

    def prepend(prefix: String, cv: ConfigValue): Set[(String, ConfigValue)] = {
      cv match {
        case obj: ConfigObject =>
          obj.toConfig.entries.map {
            case (path, cv) => s"${prefix}.$path" -> cv
          }
        case list: ConfigList =>
          import scala.collection.JavaConverters._
          list
            .listIterator()
            .asScala
            .zipWithIndex
            .flatMap {
              case (value: ConfigValue, i) => prepend(s"$prefix[$i]", value)
            }
            .toSet
        case _ => Set(prefix -> cv)
      }
    }

    val all = config.entrySet().asScala.flatMap { e =>
      val key = e.getKey
      e.getValue match {
        case list: ConfigList =>
          import scala.collection.JavaConverters._
          list.listIterator().asScala.zipWithIndex.flatMap {
            case (value: ConfigValue, i) => prepend(s"$key[$i]", value)
          }
        case cv => Set(key -> cv)
      }
    }
    all.toSet
  }

  /** @return the config as a map
    */
  def toMap = entries.toMap

  /** @return a sorted list of the origins from when the config values come
    */
  def origins: List[String] = {
    val urls = entries.flatMap {
      case (_, e) =>
        val origin = e.origin()
        Option(origin.url). //
        orElse(Option(origin.filename)). //
        orElse(Option(origin.resource)). //
        orElse(Option(origin.description)). //
        map(_.toString)
    }
    urls.toList.distinct.sorted
  }

  /**
    * Return a property-like summary of the config using the pathFilter to trim property entries
    *
    * @param obscure a function which will 'safely' replace any config values with an obscured value
    * @return a summary of the configuration
    */
  def summary(obscure: (String, String) => String = obscurePassword(_, _)): String = {
    summaryEntries(obscure).mkString(Platform.EOL)
  }

  /**
    * Return a property-like summary of the config using the 'obscure' function to mask sensitive entries
    *
    * @param obscure a function which will 'safely' replace any config values with an obscured value
    */
  def summaryEntries(obscure: (String, String) => String = obscurePassword(_, _)): Seq[StringEntry] = {
    val cro = defaultRenderOptions
    entries
      .collect {
        case (key, value) =>
          val stringValue = obscure(key, value.render(cro))
          val originString = {
            val o       = value.origin
            def resOpt  = Option(o.resource)
            def descOpt = Option(o.description)
            def line    = Option(o.lineNumber()).filterNot(_ < 0).map(": " + _).getOrElse("")
            Option(o.url()).map(_.toString).orElse(Option(o.filename)).orElse(resOpt).map(_ + line).orElse(descOpt).getOrElse("unknown origin")
          }
          import scala.collection.JavaConverters._
          val comments = value.origin().comments().asScala.toList
          StringEntry(comments, originString, key, stringValue)
      }
      .toSeq
      .sortBy(_.key)
  }

  /** The available config roots.
    *
    * e.g. of a config has
    * {{{
    *   foo.bar.x = 1
    *   java.home = /etc/java
    *   bar.enabled = true
    *   bar.user = root
    * }}}
    *
    * The 'pathRoots' would return a [bar, foo, java]
    *
    * @return a sorted list of the root entries to the config.
    */
  def pathRoots: Seq[String] = paths.map { p =>
    ConfigUtil.splitPath(p).get(0)
  }

  /** @return the configuration as a set of key/value tuples
    */
  def collectAsStrings(options: ConfigRenderOptions = defaultRenderOptions): Seq[(String, String)] =
    entries
      .map {
        case (key, value) => (key, value.render(options))
      }
      .toSeq
      .sorted

  /** @return the configuration as a map
    */
  def collectAsMap(options: ConfigRenderOptions = defaultRenderOptions): Predef.Map[String, String] = {
    collectAsStrings(options).toMap
  }

  /** @param other
    * @return the configuration representing the intersection of the two configuration entries
    */
  def intersect(other: Config): Config = {
    withPaths(other.paths)
  }

  /** @param first   the first path to include (keep)
    * @param theRest any other paths to keep
    * @return this configuration which only contains the specified paths
    */
  def withPaths(first: String, theRest: String*): Config = withPaths(first :: theRest.toList)

  /** @param paths
    * @return this configuration which only contains the specified paths
    */
  def withPaths(paths: Seq[String]): Config = {
    paths.map(config.withOnlyPath).reduce(_ withFallback _)
  }
}