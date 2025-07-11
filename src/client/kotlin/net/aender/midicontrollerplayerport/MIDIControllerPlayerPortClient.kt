package net.aender.midicontrollerplayerport

import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket
import net.minecraft.text.Text
import net.minecraft.util.Hand
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import net.minecraft.util.math.Vec3i
import net.minecraft.world.GameMode
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.sound.midi.*
import kotlin.io.path.isDirectory
import kotlin.io.path.name

object MIDIControllerPlayerPortClient : ClientModInitializer {

	private val logger = LoggerFactory.getLogger("midicontrollerplayerport")
	private var boundDevice: MidiDevice? = null
	private var boundReceiver: MidiReceiver? = null
	private val MC = MinecraftClient.getInstance()
	data class PosAction (val pos: Vec3i, val action: Int)
	private val mappedKeys = HashMap<Int, PosAction>()
	private var velocityThreshold = 20

	private val secretSettingMap = HashMap<Int, PosAction>()
	private var secretSettingEnabled = false

	private val configDirectory = File(FabricLoader.getInstance().configDir.toFile(), "midicontrollerplayerport")

	private const val SUSTAIN_ON = 10_001
	private const val SUSTAIN_OFF = 10_002
	private const val BEND_UP_FULL = 10_003
	private const val BEND_UP_HALF = 10_004
	private const val BEND_ZERO = 10_005
	private const val BEND_DOWN_HALF = 10_006
	private const val BEND_DOWN_FULL = 10_007
	private var lastBendState = BEND_ZERO

	private var showDebugMessages = false


	object MidiReceiver : Receiver {

		var isEnabled = true

		override fun close() {
		}

		override fun send(msg: MidiMessage?, timestamp: Long) {MidiReceiver
			if (!isEnabled) {
				return
			}

//			logger.info(msg.toString())
			val m = msg ?: return


//			if (m.status == ShortMessage.PITCH_BEND) {
//				logger.info("Pitch  bend")
//				// [0] ?? [1] LSB [2] MSB
//				// lsb and msb have only 7 bits of data
//				// the following mess combines two bytes of message
//				// into 14 bit value then sign extends it to 16 bits
//				val shortType = (((((m.message[2].toInt() shl 7) + m.message[1])) shl 2).toShort().toInt() shr 2).toShort()
//				logger.info("Short: $shortType     MSB: ${m.message[2].toInt()} LSB: ${m.message[1].toInt()}")
//			} else if (m.status == ShortMessage.CONTROL_CHANGE) {
//				logger.info("Control change")
//				// [0] ?? [1] control number [2] 127=on 0=off
//			} else if (m.status == ShortMessage.NOTE_ON) {
//				logger.info("Note ON")
//			} else {
//				logger.info("Unhandled message type")
//			}

			val bytes = m.message
			if (bytes.size < 3) return

			// [0] ??  [1] note  [2] velocity

			val player = MC.player ?: return
			val playerPos = player.blockPos
			val eye = player.eyePos

			val key = when (m.status) {
				ShortMessage.NOTE_ON -> bytes[1].toInt()
				ShortMessage.CONTROL_CHANGE -> {
					val controller = bytes[1].toInt() and 0x7F // MIDI is 7-bit
					val value = bytes[2].toInt() and 0x7F

					if (controller == 64) { // Sustain pedal
						if (value == 0) SUSTAIN_OFF else SUSTAIN_ON
					} else {
						debugMessage("Ignoring CC#$controller with value $value")
						return
					}
				}


				ShortMessage.PITCH_BEND -> {
//					val shortType = (((((m.message[2].toInt() shl 7) + m.message[1])) shl 2).toShort().toInt() shr 2).toShort()
//					val f = shortType / 8192.0f


					val intType: Int = (bytes[2].toInt() shl 7) + bytes[1]
					val f: Float = intType / 16384f

//					debugMessage("Bend $intType   $f")
//					debugMessage("Bend ${bytes[2].toInt()} ${bytes[1].toInt()}")

					// good for windows with div by 16384
					val currentBend =
						if (f > 0.9f) {
							BEND_UP_FULL
						} else if (f > 0.6f) {
							BEND_UP_HALF
						} else if (f < 0.1f) {
							BEND_DOWN_FULL
						} else if (f < 0.4f) {
							BEND_DOWN_HALF
						} else {
							BEND_ZERO
						}

					// good for linux with div by 16383
//					val currentBend =
//						if (f > 0.5f) {
//							if (f < 0.6f) {
//								BEND_DOWN_FULL
//							} else if (f < 0.95f) {
//								BEND_DOWN_HALF
//							} else {
//								BEND_ZERO
//							}
//						} else {
//							if (f > 0.4f) {
//								BEND_UP_FULL
//							} else if (f > .05f) {
//								BEND_UP_HALF
//							} else {
//								BEND_ZERO
//							}
//						}


					// good for linux with sign extend
//					val currentBend =
//					if (f > .9f) {
//						BEND_UP_FULL
//					} else if (f > .2f) {
//						BEND_UP_HALF
//					} else if (f < -.9f) {
//						BEND_DOWN_FULL
//					} else if (f < -.2f) {
//						BEND_DOWN_HALF
//					} else {
//						BEND_ZERO
//					}
//					debugMessage("$currentBend")
					if (currentBend != lastBendState) {
						lastBendState = currentBend
						debugMessage("PLAYING $lastBendState ( $intType )")
						currentBend
					} else {
						-1
					}
				}
				else -> -1
			}

			val velocity = if (m.status == ShortMessage.NOTE_ON) bytes[2].toInt() else 127;
//			logger.info("$velocity")
			if (velocity < velocityThreshold) {
				return
			}

			if (secretSettingEnabled and secretSettingMap.containsKey(key)) {
				val offset = secretSettingMap[key]?.pos
				val target = playerPos.add(offset)
				val action = secretSettingMap[key]?.action

				if ((action != null) and (action!! >= 0)) {
					val dir = when (action) {
						1 -> Direction.NORTH
						2 -> Direction.EAST
						3 -> Direction.SOUTH
						4 -> Direction.WEST
						else -> Direction.UP
					}
					val wtf = BlockHitResult(Vec3d(target.x+0.5,target.y+0.5,target.z+0.5), dir, target, false)
					val packet = PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, wtf, 0)
					MC.networkHandler?.sendPacket(packet)
				} else {
					MC.interactionManager?.attackBlock(target, Direction.UP)
				}
			}

			if (!mappedKeys.containsKey(key)) {
				return
			}


			val offset = mappedKeys[key]?.pos
			val target = playerPos.add(offset)
			val action = mappedKeys[key]?.action ?: return

			if (action >= 0) {
				val dir = when (action) {
					1 -> Direction.NORTH
					2 -> Direction.EAST
					3 -> Direction.SOUTH
					4 -> Direction.WEST
					else -> Direction.UP
				}
				val wtf = BlockHitResult(Vec3d(target.x+0.5,target.y+0.5,target.z+0.5), dir, target, false)
				val packet = PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, wtf, 0)
				MC.networkHandler?.sendPacket(packet)
			} else {
				MC.interactionManager?.attackBlock(target, Direction.UP)
			}

			// probably no need
			//val dir = Direction.getFacing(0.5+target.x-eye.x, 0.5+target.y-eye.y, 0.5+target.z-eye.z)

		}

	}

	override fun onInitializeClient() {
		// This entrypoint is suitable for setting up client-specific logic, such as rendering.

		ClientCommandRegistrationCallback.EVENT.register({ dispatcher, registry ->
			dispatcher.register(
				ClientCommandManager.literal("midi")
					.then(ClientCommandManager.literal("enable")
						.executes { context ->
							enableReceiver(true)
							1
						})
					.then(ClientCommandManager.literal("disable")
						.executes { context ->
							enableReceiver(false)
							1
						})
					.then(ClientCommandManager.literal("connect")
						.executes { context ->
							handleConnect()
							1
						})
					.then(ClientCommandManager.literal("disconnect")
						.executes { context ->
							handleDisconnect()
							1
						})
					.then(ClientCommandManager.literal("reloadConfig")
						.then(ClientCommandManager.argument("file", StringArgumentType.string())
							.suggests { context, builder ->
								if (configDirectory.isDirectory) {
									Files.list(configDirectory.toPath())
										.filter { f -> !f.isDirectory() }
										.forEach { f ->
											if (f.name.contains(' ')) {
												builder.suggest("\"${f.name}\"")
											} else {
												builder.suggest(f.name)
											}
										}
								}
								builder.buildFuture()
							}
							.executes { context ->
								val fName = StringArgumentType.getString(context, "file")
								reloadConfigMap(fName, false)
								1
							}
							.then(ClientCommandManager.literal("append")
								.executes {  context ->
									val fName = StringArgumentType.getString(context, "file")
									reloadConfigMap(fName, true)
									1
								})
							.then(ClientCommandManager.literal("secret")
								.executes { context ->
									val fName = StringArgumentType.getString(context, "file")
									reloadConfigMap(fName, true, true)
									1
								})))
					.then(ClientCommandManager.literal("debug")
						.executes { context ->
							tryCreateConfigFile()
							chatMessage("[MIDI] uhm now it shouldve downloaded some cool files ig")
							1
						})
			)
		})
	}

	private fun handleConnect() {
		if (boundDevice != null) {
			chatMessage("§c[MIDI] Already connected to ${boundDevice?.deviceInfo?.name}")
			return
		}

		val infos = MidiSystem.getMidiDeviceInfo()
		var found = false
		infos.forEach { i ->
			if (found) return@forEach
			val device = MidiSystem.getMidiDevice(i)
			device.open()
			val transmitters = device.transmitters
			if (transmitters.isEmpty()) {
				device.close()
				return@forEach
			}
			logger.info("Connecting with ${i.name} ${i.description}")
			chatMessage("§b[MIDI] Connecting with ${i.name}")
			boundDevice = device
			boundReceiver = MidiReceiver
			transmitters[0].receiver = boundReceiver
			found = true
		}
		if (!found) {
			chatMessage("§c[MIDI] No devices found")
		}
	}

	private fun handleDisconnect() {
		if (boundDevice == null) {
			chatMessage("§c[MIDI] There are no devices connected")
			return
		}

		chatMessage("§b[MIDI] Disconnecting with ${boundDevice!!.deviceInfo.description}")
		boundDevice!!.close()
		boundDevice = null
		boundReceiver = null
	}

	private fun chatMessage(msg: String) {
		MC.inGameHud.chatHud.addMessage(Text.of(msg))
	}

	private fun debugMessage(msg: String) {
		if (showDebugMessages) {
			chatMessage(msg)
		}
	}

	private fun enableReceiver(enable: Boolean) {
		if (boundDevice == null) {
			chatMessage("§c[MIDI] No devices connected")
			return
		}

		boundReceiver?.isEnabled = enable
		if (enable) {
			chatMessage("§b[MIDI] Enabled")
			if (MC.interactionManager?.currentGameMode != GameMode.SURVIVAL) {
				chatMessage("§a[MIDI] Remember to switch to survival")
			}
		} else {
			chatMessage("§b[MIDI] Disabled")
		}
	}

	private fun tryCreateConfigFile() {
		configDirectory.mkdirs()

		val profilesDir = File(configDirectory, "profiles")
		val schematicsDir = File(configDirectory, "schematics")

		downloadFile(
			"https://raw.githubusercontent.com/AenderErSeje/MIDIControllerPlayerPort/main/src/main/resources/config/profiles/Test1.txt",
			File(profilesDir, "Test1.txt")
		)

		downloadFile(
			"https://raw.githubusercontent.com/AenderErSeje/MIDIControllerPlayerPort/main/src/main/resources/config/profiles/TestSecret.txt",
			File(profilesDir, "TestSecret.txt")
		)

		// Download schematic file
		downloadFile(
			"https://raw.githubusercontent.com/AenderErSeje/MIDIControllerPlayerPort/main/src/main/resources/config/schematics/midipiano.schem",
			File(schematicsDir, "midipiano.schem")
		)
	}

	private fun downloadFile(remoteUrl: String, targetFile: File) {
		try {
			targetFile.parentFile.mkdirs()
			URL(remoteUrl).openStream().use { input ->
				Files.copy(input, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
			}
			logger.info("Downloaded $remoteUrl to ${targetFile.absolutePath}")
			chatMessage("§a[MIDI] Downloaded ${targetFile.name}")
		} catch (e: Exception) {
			logger.error("Failed to download $remoteUrl: ${e.message}")
			chatMessage("§c[MIDI] Failed to download ${targetFile.name}: ${e.message}")
		}
	}


	private fun reloadConfigMap(fName: String, append: Boolean, secret: Boolean = false) {
		chatMessage("§b[MIDI] Reloading key mappings")

		if (secret) {
			secretSettingMap.clear()
			if (fName.isEmpty()) {
				secretSettingEnabled = false
				chatMessage("§b[MIDI] Disabling secret..")
				return
			} else {
				chatMessage("§b[MIDI] Loading secret settings, shhhhhh..")
				secretSettingEnabled = true
			}
		}

		val fileDir = File(configDirectory, "profiles")
		val file = File(fileDir, fName)

		if (!file.exists()) {
			chatMessage("§c[MIDI] Profile '$fName' not found in /config/profiles")
			return
		}

		if (!append && !secret) {
			mappedKeys.clear()
		}

		try {
			file.forEachLine { line ->
				val s = line.split(':')
				if (s[0] == "vel") {
					velocityThreshold = s[1].toInt()
					return@forEachLine
				}

				val key = s[0].toInt()
				val x = s[1].toInt()
				val y = s[2].toInt()
				val z = s[3].toInt()
				val action = if (s.size > 4) s[4].toInt() else -1

				if (mappedKeys.containsKey(key) || secretSettingMap.containsKey(key)) {
					debugMessage("Skipping duplicate key $key")
					return@forEachLine
				}

				val posAction = PosAction(Vec3i(x, y, z), action)
				if (secret) {
					secretSettingMap[key] = posAction
				} else {
					mappedKeys[key] = posAction
				}
			}
		} catch (e: Exception) {
			chatMessage("§cAn error occurred while loading the profile")
			chatMessage("§c${e.message}")
		}
	}
}
