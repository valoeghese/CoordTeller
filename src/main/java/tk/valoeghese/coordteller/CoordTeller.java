package tk.valoeghese.coordteller;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.server.ServerTickCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.util.math.Vec3d;
import tk.valoeghese.zoesteriaconfig.api.ZoesteriaConfig;
import tk.valoeghese.zoesteriaconfig.api.container.Container;
import tk.valoeghese.zoesteriaconfig.api.container.WritableConfig;
import tk.valoeghese.zoesteriaconfig.api.template.ConfigTemplate;
import tk.valoeghese.zoesteriaconfig.api.template.ConfigTemplate.Builder;

public class CoordTeller implements ModInitializer {
	@Override
	public void onInitialize() {
		// get config file
		File configFile = new File(FabricLoader.getInstance().getConfigDirectory().getPath() + "/coordteller.zfg");

		// If config file does not exist we created and mark that we need to write to it
		boolean write = false;
		try {
			write = configFile.createNewFile();
		} catch (IOException e) {
			e.printStackTrace();
		}

		// Load config from file, with default fallback values
		// ... I should really add an option to add comments for WritableConfigs in ZoesteriaConfig
		config = ZoesteriaConfig.loadConfigWithDefaults(
				configFile,
				((Builder) ConfigTemplate.builder()
						.addList("coordinateReaders", list -> {
							list.add("magma_dude"); // examples
							list.add("Dinnerbone");
						})
						.addList("coordinateTargets", list -> {
							list.add("Notch"); // examples
							list.add("JohnSmith");
							list.add("YaYeet");
						})
						.addContainer("tellInfo", tellInfo -> tellInfo
								.addDataEntry("targetTrackingInaccuracyX", "40")
								.addDataEntry("targetTrackingInaccuracyY", "20")
								.addDataEntry("targetTrackingInaccuracyZ", "40")
								.addDataEntry("tellTargetY", "true"))
						.addContainer("targetingMethod", targetingMethod -> targetingMethod
								.addDataEntry("minTargetCount", "1")
								.addDataEntry("maxTargetCount", "2")
								.addDataEntry("pickTargetsPerReader", "false")
								.addDataEntry("minTimeBetweenTellings", "60.0")
								.addDataEntry("maxTimeBetweenTellings", "360.0"))
						.addDataEntry("useUUIDs", "false"))
				.build());
		
		// Write config if it doesn't exist
		if (write) {
			try {
				config.writeToFile(configFile);
			} catch (Throwable t) {
				t.printStackTrace();
			}
		}

		// Set values from config
		coordinateReaders = config.getList("coordinateReaders").toArray(new String[0]);
		coordinateTargets = config.getList("coordinateTargets").toArray(new String[0]);
		useUUIDs = config.getBooleanValue("useUUIDs");

		Container tellInfo = config.getContainer("tellInfo");
		inaccuracyX = tellInfo.getIntegerValue("targetTrackingInaccuracyX");
		inaccuracyY = tellInfo.getIntegerValue("targetTrackingInaccuracyY");
		inaccuracyZ = tellInfo.getIntegerValue("targetTrackingInaccuracyZ");
		tellTargetY = tellInfo.getBooleanValue("tellTargetY");

		Container targetingMethod = config.getContainer("targetingMethod");
		minTargetCount = targetingMethod.getIntegerValue("minTargetCount");
		maxTargetCount = targetingMethod.getIntegerValue("maxTargetCount");
		pickTargetsPerReader = targetingMethod.getBooleanValue("pickTargetsPerReader");
		minTimeBetweenTellings = (int) (targetingMethod.getDoubleValue("minTimeBetweenTellings") * 100d); // in centi-seconds
		deltaTimeBetweenTellings = (int) (targetingMethod.getDoubleValue("maxTimeBetweenTellings") * 100d) - minTimeBetweenTellings;

		// PreConditions
		if (maxTargetCount < minTargetCount) {
			throw new RuntimeException("maxTargetCount must be greater than or equal to minTargetCount!");
		}
		if (maxTargetCount > coordinateTargets.length) {
			throw new RuntimeException("maxTargetCount must be less than or equal to the length of coordinateTargets!");
		}

		// Register the main tick method
		ServerTickCallback.EVENT.register(server -> {
			int maxTargetCountToUse = maxTargetCount;

			if (maxTargetCount > server.getCurrentPlayerCount()) {
				if (minTargetCount > server.getCurrentPlayerCount()) {
					return;
				} else {
					maxTargetCountToUse = server.getCurrentPlayerCount();
				}
			}

			// check if the time is correct for another telling
			long currentTime = System.currentTimeMillis();
			if (currentTime > nextTimeElapsed) {
				nextTimeElapsed = currentTime + (long) minTimeBetweenTellings + (long) RAND.nextInt(deltaTimeBetweenTellings);

				// get player source and do stuff
				PlayerManager playerManager = server.getPlayerManager();
				Function<String, ServerPlayerEntity> getPlayer = useUUIDs ? sIn -> playerManager.getPlayer(UUID.fromString(sIn)) : sIn -> playerManager.getPlayer(sIn);
				Set<String> targets = null;
				Map<ServerPlayerEntity, Vec3d> coordinateCache = new HashMap<>();

				for (String reader : coordinateReaders) {
					ServerPlayerEntity readerPlayer = getPlayer.apply(reader);
					if (readerPlayer == null) {
						continue;
					}

					// Select targets
					if (targets == null || pickTargetsPerReader) {
						targets = new HashSet<>();
						int deltaTargetCount = maxTargetCountToUse - minTargetCount;
						deltaTargetCount = deltaTargetCount < 1 ? 1 : deltaTargetCount;
						int targetCount = RAND.nextInt(deltaTargetCount) + minTargetCount;

						for (int i = 0; i < targetCount; ++i ) {
							targets.add(coordinateTargets[RAND.nextInt(coordinateTargets.length)]);
						}

						if (targets.size() < minTargetCount) {
							int attemptsLeft = 3;
							int index = RAND.nextInt(coordinateTargets.length);

							while (attemptsLeft --> 0) {
								String selectedUser = coordinateTargets[index++];
								if (targets.contains(selectedUser)) {
									continue;
								}
								targets.add(selectedUser);
								if (index >= coordinateTargets.length) {
									index = 0;
								}

								int tSize = targets.size();
								if (tSize < minTargetCount) {
									boolean randFlag = true; // if the following code causes an error then it is probably at a stage where it should be true
									try {
										randFlag = RAND.nextInt(deltaTargetCount--) == 0;
									} catch (Throwable t) {}

									if (tSize >= maxTargetCountToUse || randFlag) {
										break;
									}
								}
							}
						}
					}

					// Give target coordinates to reader, with inaccuracies
					targets.forEach(targetString -> {
						ServerPlayerEntity target = getPlayer.apply(targetString);
						if (target == null) {
							return;
						}

						Vec3d coordsForDisplay = pickTargetsPerReader ?
							getCoordsForDisplay(target) :
							coordinateCache.computeIfAbsent(target, CoordTeller::getCoordsForDisplay);

						StringBuilder message = new StringBuilder("§l")
								.append(target.getName().asString())
								.append("§r is near the coordinates (")
								.append((int) coordsForDisplay.getX())
								.append(", ");
						if (tellTargetY) {
							message.append((int) coordsForDisplay.getY())
							.append(", ");
						}
						message.append((int) coordsForDisplay.getZ())
						.append(")");

						readerPlayer.sendMessage(new LiteralText(message.toString()));
					});
				}
			}
		});
	}

	private static Vec3d getCoordsForDisplay(ServerPlayerEntity player) {
		int xOffset = inaccuracyX == 0 ? 0 : RAND.nextInt(2 * inaccuracyX) - inaccuracyX;
		int yOffset = inaccuracyY == 0 ? 0 : RAND.nextInt(2 * inaccuracyY) - inaccuracyY;
		int zOffset = inaccuracyZ == 0 ? 0 : RAND.nextInt(2 * inaccuracyZ) - inaccuracyZ;
		Vec3d result = player.getPos().add(xOffset, yOffset, zOffset);
		if (result.getY() < 0) {
			result = new Vec3d(result.x, 0, result.z);
		}
		return result;
	}

	private static WritableConfig config;

	private static String[] coordinateReaders, coordinateTargets;
	private static int inaccuracyX, inaccuracyY, inaccuracyZ;
	private static int minTargetCount, maxTargetCount;
	private static boolean tellTargetY, useUUIDs, pickTargetsPerReader;
	private static int minTimeBetweenTellings, deltaTimeBetweenTellings; // in centi seconds
	private static long nextTimeElapsed = 0L;

	private static final Random RAND = new Random();
}
