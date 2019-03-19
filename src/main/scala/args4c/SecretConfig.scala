package args4c

import java.net.URL
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, Paths, StandardOpenOption}

import com.typesafe.config._

import scala.annotation.tailrec
import scala.compat.Platform
import scala.util.Properties

/**
  * Makes available a means to initialize a sensitive, encrypted config file via [[SecretConfig.writeSecretsUsingPrompt]] and [[ConfigApp.secretConfigForArgs]]
  *
  * The idea is that a (service) user-only readable, password-protected AES encrypted config file can be set up via reading entries from
  * standard input, and an application an use those configuration entries thereafter by taking the password from standard input.
  */
object SecretConfig {

  // format: off
  type Prompt = String

  /**
    * The environment variable which, if set, will be used to decrypt an encrypted config file (e.g. "--secret" for the default or "--secret=password.conf" for specifying one)
    */
  val SecretEnvVariableName = "CONFIG_SECRET"

  /**
    * prompt for and set some secret values
    *
    * @param readLine a means to accept user input for a particular prompt
    * @return the path to the encrypted configuration file
    */
  def writeSecretsUsingPrompt(secretConfigFilePath : String, readLine: Prompt => String): Path = {
    val configPath = readSecretConfigPath(secretConfigFilePath, readLine)
    val permissions = readPermissions(readLine)

    pathAsFile(configPath).map(_.getParent).foreach { dir =>
      if (!Files.exists(dir)) {
        Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(permissions))
      }
    }

    val (config, previousConfigPassword) = {
      val newConfig = readSecretConfig(readLine)
      val (existingConfig, previousPwd) = pathToBytes(configPath) match {
        case Some(bytes) =>
          val pwd: Array[Byte] = readLine(s"A config already exists at $configPath, enter password:").getBytes("UTF-8")

          (bytesAsConfig(bytes, pwd, readLine), Option(pwd))
        case None => ConfigFactory.empty -> None
      }

      val options = ConfigParseOptions.defaults()
        .setSyntax(ConfigSyntax.CONF)
        .setOriginDescription("sensitive")
        .setAllowMissing(false)

      ConfigFactory.parseString(newConfig, options).withFallback(existingConfig) -> previousPwd
    }

    val pwd: Array[Byte] = {
      val configPasswordPrompt = if (previousConfigPassword.isEmpty) "Config Password:" else "New Config Password (or blank to reuse the existing one):"
      readLine(configPasswordPrompt) match {
        case "" if previousConfigPassword.nonEmpty =>
          val same = previousConfigPassword.get
          previousConfigPassword.foreach(Encryption.clear)
          same
        case password => password.getBytes("UTF-8")
      }
    }

    import args4c.implicits._
    val encrypted = config.encrypt(pwd)
    Encryption.clear(pwd)

    import StandardOpenOption._
    // touch the file to set the permissions
    Files.write(configPath, Array[Byte](), TRUNCATE_EXISTING, CREATE, CREATE_NEW, SYNC)
    Files.setPosixFilePermissions(configPath, permissions)

    // now create the actual file
    Files.write(configPath, encrypted, APPEND, SYNC)
  }

  /**
    * read the configuration from the given path, prompting for the password via 'readLine' should the  [[SecretEnvVariableName]]
    * environment variable not be set
    *
    * @param pathToEncryptedConfig the path pointing at the encrypted config
    * @param readLine the readline function to get user input
    * @return a configuration if the file exists
    */
  def readSecretConfig(pathToEncryptedConfig: String, readLine: String => String): Option[Config] = {
    val configBytesOpt = pathToBytes(pathToEncryptedConfig)
    configBytesOpt.map { bytes =>
      val pwd = envOrProp(SecretEnvVariableName).getOrElse(readLine("Config Password:")).getBytes("UTF-8")
      val conf = bytesAsConfig(bytes, pwd, readLine)
      Encryption.clear(pwd)
      conf
    }
  }

  private def bytesAsConfig(bytes : Array[Byte], pwd: Array[Byte], readLine: String => String): Config = {
    val configText = Encryption.decryptAES(pwd, bytes, bytes.length)
    ConfigFactory.parseString(configText)
  }

  private def readSecretConfig(readLine: Prompt => String): String = {
    import implicits._
    readNext(Map.empty, readLine).map {
      case (key, value) => s"$key = ${value.quoted}"
    }.mkString(Platform.EOL)
  }

  @tailrec
  private def readNext(entries: Map[String, String], readLine: Prompt => String): Map[String, String] = {
    readLine("Add config path in the form <key>=<value> (leave blank when finished):").trim match {
      case KeyValue(key, value) =>
        readNext(entries.updated(key, value), readLine)
      case "" => entries
      case _ =>
        System.err.println("Invalid key=value pair. Entries should be in the for <path.to.config.entry>=some sensitive value")
        entries
    }
  }

  private def readPermissions(readLine: Prompt => String) = {
    val permString = readLine(s"Config Permissions ($defaultPermissions):") match {
      case "" => defaultPermissions
      case other => other
    }

    PosixFilePermissions.fromString(permString)
  }

  private def readSecretConfigPath(pathToSecretConfigFile : String, readLine: Prompt => String): String = {
    readLine(saveSecretPrompt(pathToSecretConfigFile)) match {
      case "" => defaultSecretConfigPath()
      case path => path
    }
  }

  private[args4c] def defaultPermissions = "rwx------"

  private[args4c] def saveSecretPrompt(configPath: String) = s"Save secret config to (${configPath}):"

  private[args4c] def defaultSecretConfigPath(workDir: String = Properties.userDir): String = s"${workDir}/.config/secret.conf"

  // format: on
}
