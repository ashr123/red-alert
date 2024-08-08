package io.github.ashr123.red.alert;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.ashr123.option.*;
import io.github.ashr123.timeMeasurement.Result;
import io.github.ashr123.timeMeasurement.TimeMeasurement;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.util.datetime.FixedDateFormat;
import picocli.CommandLine;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@CommandLine.Command(name = "java -jar red-alert-listener.jar",
		mixinStandardHelpOptions = true,
		versionProvider = Listener.class,
		showDefaultValues = true,
		description = "An App that can get \"red alert\"s from IDF's Home Front Command.")
public class Listener implements Runnable, CommandLine.IVersionProvider {
	private static final TypeReference<List<District>> DISTRICTS_TYPE_REFERENCE = new TypeReference<>() {
	};
	private static final TypeReference<List<AlertTranslation>> ALERTS_TRANSLATION_TYPE_REFERENCE = new TypeReference<>() {
	};
	private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(
					FixedDateFormat.FixedFormat.DEFAULT.getPattern(),
					Locale.getDefault(Locale.Category.FORMAT)
			)
			.withZone(ZoneId.systemDefault());
	private static final Logger LOGGER = LogManager.getLogger();
	private static final ObjectMapper JSON_MAPPER = new JsonMapper()
			.enable(SerializationFeature.INDENT_OUTPUT)
			.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
//			.disable(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS)
			.findAndRegisterModules();
	private static final Configuration DEFAULT_CONFIGURATION = new Configuration(
			false,
			false,
			true,
			true,
			false,
			Duration.ofSeconds(10),
			LanguageCode.HE,
			Level.INFO,
			Collections.emptySet()
	);
	private static final HttpResponse.BodyHandler<InputStream> RESPONSE_BODY_HANDLER = HttpResponse.BodyHandlers.ofInputStream();
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL) //?
			.build();
//	private static final Pattern
//			VAR_ALL_DISTRICTS = Pattern.compile("^.*=\\s*", Pattern.MULTILINE),
//			BOM = Pattern.compile("﻿");
//	private static final Collator COLLATOR = Collator.getInstance(Locale.ROOT);
	@CommandLine.Option(names = {"-c", "--configuration-file"},
			paramLabel = "configuration file",
			defaultValue = "red-alert-listener.conf.json",
			description = "Enter custom path to configuration file.")
	private File configurationFile;
	private volatile boolean isContinue = true;
	private Configuration configuration = DEFAULT_CONFIGURATION;
	private long configurationLastModified = 1;
	private List<String> districtsNotFound = Collections.emptyList();
	/**
	 * Will be updated once a day from IDF's Home Front Command's server.
	 */
	private volatile Map<String, AreaTranslationProtectionTime> districts;
	private volatile HttpRequest httpRequest;

	private Listener() {
	}

	public static void main(String... args) {
		new CommandLine(Listener.class)
				.setCaseInsensitiveEnumValuesAllowed(true)
				.execute(args);
	}

	private static void setLoggerLevel(Level level) {
		final LoggerContext loggerContext = (LoggerContext) LogManager.getContext(false);
		loggerContext.getConfiguration().getLoggerConfig(LOGGER.getName()).setLevel(level);
		loggerContext.updateLoggers();
	}

	private static void printHelpMsg() {
		System.err.println("""
				\t• Enter "t" to perform sound test.
				\t• Enter "c" to clear the screen.
				\t• Enter "r" to refresh the districts translation dictionary.
				\t• Enter "l" to reload configuration file.
				\t• Enter "q" to quit.
				\t• Enter "h" to display this help massage.""");
	}

	private static void sleep() {
		try {
			Thread.sleep(1000);
		} catch (InterruptedException interruptedException) {
			//noinspection CallToPrintStackTrace
			interruptedException.printStackTrace(); //TODO think about
		}
	}

	private static Set<String> getTranslations(Collection<AreaTranslationProtectionTime> translatedData) {
		return translatedData.parallelStream().unordered()
				.map(AreaTranslationProtectionTime::translation)
				.collect(Collectors.toSet());
	}

	private String areaAndTranslatedDistrictsToString(CharSequence headline,
													  List<AreaTranslationProtectionTime> districtsByAreaName,
													  int cat) {
		final Function<AreaTranslationProtectionTime, String> toString = cat == 1 || cat == 101 ?
				areaTranslationProtectionTime -> areaTranslationProtectionTime.translation() + " (" + configuration.languageCode().getTimeTranslation(areaTranslationProtectionTime.protectionTime()) + ")" :
				AreaTranslationProtectionTime::translation;
		return districtsByAreaName.parallelStream().unordered()
				.collect(Collectors.groupingByConcurrent(AreaTranslationProtectionTime::translatedAreaName))
				.entrySet().parallelStream().unordered()
				.sorted(Map.Entry.comparingByKey())
				.map(areaNameAndDistricts -> areaNameAndDistricts.getValue().parallelStream().unordered()
						.sorted(Comparator.comparing(AreaTranslationProtectionTime::translation))
						.map(toString)
						.collect(Collectors.joining(
								"," + System.lineSeparator() + "\t\t",
								areaNameAndDistricts.getKey() + ":" + System.lineSeparator() + "\t\t",
								""
						)))
				.collect(Collectors.joining(
						"," + System.lineSeparator() + "\t",
						headline + ":" + System.lineSeparator() + "\t",
						System.lineSeparator()
				));
	}

	@CommandLine.Command(name = "get-remote-districts-as-json",
			mixinStandardHelpOptions = true,
			versionProvider = Listener.class,
			showDefaultValues = true,
			description = "Gets all supported districts translation from Hebrew to the appropriate translation from IDF's Home Front Command's server and print it to stdout (No need for configuration file).")
	private void getRemoteDistrictsAsJSON(
			@CommandLine.Option(names = {"-l", "--language"},
					paramLabel = "language code",
					required = true,
					description = "Which language's translation to get? Valid values: ${COMPLETION-CANDIDATES} (case insensitive).")
			LanguageCode languageCode,
			@CommandLine.Option(names = {"-t", "--timeout"},
					paramLabel = "timeout",
					defaultValue = "PT10S",
					description = "Timeout for connecting to IDF's Home Front Command's server in ISO 8601 (Duration) format, see https://en.wikipedia.org/wiki/ISO_8601#Durations.")
			Duration timeout,
			@CommandLine.Option(names = {"-L", "--logger-level"},
					paramLabel = "logger level",
					defaultValue = "INFO",
					converter = LoggerLevelConverter.class,
					description = "Level of logger. Valid values: OFF, FATAL, ERROR, WARN, INFO, DEBUG, TRACE, ALL (case insensitive).")
			Level loggerLevel
	) throws IOException, InterruptedException {
		System.out.println(JSON_MAPPER.writeValueAsString(startSubcommandInputThread(
				languageCode,
				timeout,
				loggerLevel,
				District::label
		)));
	}

	@CommandLine.Command(name = "get-remote-districts-as-json-to-file",
			mixinStandardHelpOptions = true,
			versionProvider = Listener.class,
			showDefaultValues = true,
			description = "Gets all supported districts translation from Hebrew from IDF's Home Front Command's server and print it to file, grouped by area-name, including also protection time in seconds (No need for configuration file).")
	private void getRemoteDistrictsAsJSONToFile(
			@CommandLine.Option(names = {"-o", "--output"},
					paramLabel = "file",
					defaultValue = "districts.json",
					description = "Where to save received districts.")
			File file,
			@CommandLine.Option(names = {"-l", "--language"},
					paramLabel = "language code",
					required = true,
					description = "Which language's translation to get? Valid values: ${COMPLETION-CANDIDATES} (case insensitive).")
			LanguageCode languageCode,
			@CommandLine.Option(names = {"-t", "--timeout"},
					paramLabel = "timeout",
					defaultValue = "PT10S",
					description = "Timeout for connecting to IDF's Home Front Command's server in ISO 8601 (Duration) format, see https://en.wikipedia.org/wiki/ISO_8601#Durations.")
			Duration timeout,
			@CommandLine.Option(names = {"-L", "--logger-level"},
					paramLabel = "logger level",
					defaultValue = "INFO",
					converter = LoggerLevelConverter.class,
					description = "Level of logger. Valid values: OFF, FATAL, ERROR, WARN, INFO, DEBUG, TRACE, ALL (case insensitive).")
			Level loggerLevel
	) throws IOException, InterruptedException {
		JSON_MAPPER.writeValue(
				file,
				startSubcommandInputThread(
						languageCode,
						timeout,
						loggerLevel,
						Function.identity()
				)
						.entrySet().parallelStream().unordered()
						.collect(Collectors.groupingByConcurrent(
								entry -> entry.getValue().areaname(),
								Collectors.toConcurrentMap(
										Map.Entry::getKey,
										entry -> new FileDistrictData(
												entry.getValue().label(),
												entry.getValue().migun_time()
										)
								)
						))
		);
	}

	private <D> Map<String, D> startSubcommandInputThread(LanguageCode languageCode,
														  Duration timeout,
														  Level level,
														  Function<District, D> districtMapper) throws InterruptedException {
		final CountDownLatch startSignal = new CountDownLatch(1);
		Thread.startVirtualThread(() -> {
			try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
				System.err.println("Enter \"q\" to quit");
				startSignal.countDown();
				while (isContinue)
					switch (scanner.nextLine().strip()) {
						case "" -> {
						}
						case "q" -> {
							System.err.println("Quiting...");
							isContinue = false;
						}
						default -> System.err.println("""
								Unrecognized command!
								Enter "q" to quit""");
					}
			} catch (NoSuchElementException ignored) {
			}
		});
		setLoggerLevel(level);
		startSignal.await();
		return loadRemoteDistricts(languageCode, timeout, districtMapper);
	}

	private static StringBuilder redAlertToString(long contentLength,
												  TemporalAccessor alertsLastModified,
												  String translatedTitle,
												  String translatedDescription,
												  String translatedData,
												  StringBuilder output) {
		return output.append("Translated Title: ").append(translatedTitle).append(System.lineSeparator())
				.append("Translated Description: ").append(translatedDescription).append(System.lineSeparator())
				.append("Content Length: ").append(contentLength).append(" bytes").append(System.lineSeparator())
				.append("Last Modified Date: ").append(DATE_TIME_FORMATTER.format(alertsLastModified)).append(System.lineSeparator())
				.append("Current Date: ").append(DATE_TIME_FORMATTER.format(Instant.now())).append(System.lineSeparator())
				.append(translatedData);
	}

	/**
	 * <li><a href=https://www.oref.org.il/districts/districts_heb.json>districts_heb.json</a></li>
	 * <li><a href=https://www.oref.org.il/districts/districts_eng.json>districts_eng.json</a></li>
	 * <li><a href=https://www.oref.org.il/districts/districts_rus.json>districts_rus.json</a></li>
	 * <li><a href=https://www.oref.org.il/districts/districts_arb.json>districts_arb.json</a></li>
	 */
	private <T> Map<String, T> loadRemoteDistricts(LanguageCode languageCode,
												   Duration timeout,
												   Function<District, T> districtMapper) {
		LOGGER.info("Getting districts from IDF's Home Front Command's server...");
		while (isContinue) {
			try {
				final Result<Map<String, T>> result = TimeMeasurement.measureAndExecuteCallable(() -> {
					final HttpResponse<InputStream> httpResponse = HTTP_CLIENT.send(
							HttpRequest.newBuilder(URI.create("https://alerts-history.oref.org.il/Shared/Ajax/GetDistricts.aspx?lang=" + languageCode.name().toLowerCase(Locale.ROOT)))
									.header("Accept", "application/json")
									.timeout(timeout)
									.build(),
							RESPONSE_BODY_HANDLER
					);
					try (InputStream body = httpResponse.body()) {
						if (httpResponse.statusCode() >= 200 && httpResponse.statusCode() < 300)
							return JSON_MAPPER.readValue(
											/*VAR_ALL_DISTRICTS.matcher(httpResponse.*/body/*()).replaceFirst("")*/,
											DISTRICTS_TYPE_REFERENCE
									)
									.parallelStream().unordered()
									.collect(Collectors.toConcurrentMap(
											District::label_he,
											districtMapper,
											(value1, value2) -> {
												LOGGER.trace("value1: {}, value2: {}", value1, value2);
												return value2;
											}
									));
					}
					LOGGER.error("Got bad response status code: {}", httpResponse.statusCode());
					return Collections.emptyMap();
				});
				if (result.getResult().isEmpty()) {
					sleep();
					continue;
				}
				LOGGER.info("Done (took {} milliseconds, got {} districts)", result.getTimeTaken(), result.getResult().size());
				return result.getResult();
			} catch (JsonParseException e) {
				LOGGER.error("JSON parsing error: {}", e.toString());
			} catch (Exception e) {
				LOGGER.debug("Failed to get data for language code {}: {}. Trying again...", languageCode, e.toString());
			}
			sleep();
		}
		return Collections.emptyMap();
	}

	private void printDistrictsNotFoundWarning() {
		if (!districtsNotFound.isEmpty())
			LOGGER.warn("Those districts don't exist: {}", districtsNotFound);
	}

	@Override
	public String[] getVersion() {
		return new String[]{"Red Alert Listener v" + getClass().getPackage().getImplementationVersion()};
	}

	private void loadConfiguration() throws IOException {
		final long configurationLastModifiedTemp = configurationFile.lastModified();
		final LanguageCode oldLanguageCode = configuration.languageCode();
		final Duration oldTimeout = configuration.timeout();
		if (configurationLastModifiedTemp > configurationLastModified) {
			LOGGER.info("(Re)Loading configuration from file \"{}\"", configurationFile);
			configuration = JSON_MAPPER.readValue(configurationFile, Configuration.class);
			configurationLastModified = configurationLastModifiedTemp;
			if (districts == null || !oldLanguageCode.equals(configuration.languageCode()))
				refreshDistrictsTranslation();
			districtsNotFound = (configuration.districtsOfInterest().size() > 2 ?
					new ArrayList<>(configuration.districtsOfInterest()) :
					configuration.districtsOfInterest()).parallelStream().unordered()
					.filter(Predicate.not(getTranslations(new ArrayList<>(districts.values()))::contains))
					.toList();
			printDistrictsNotFoundWarning();
			setLoggerLevel(configuration.logLevel());
			if (httpRequest == null || !oldTimeout.equals(configuration.timeout()))
				httpRequest = HttpRequest.newBuilder(URI.create("https://www.oref.org.il/WarningMessages/alert/alerts.json"))
						.header("Accept", "application/json")
//						.header("X-Requested-With", "XMLHttpRequest")
//						.header("Referer", "https://www.oref.org.il/12481-" + configuration.languageCode().name().toLowerCase(Locale.ROOT) + "/Pakar.aspx")
						.timeout(configuration.timeout())
						.build();
		} else if (configurationLastModifiedTemp == 0 && configurationLastModified != 0) {
			LOGGER.warn("couldn't find \"{}\", using default configuration", configurationFile);
			configuration = DEFAULT_CONFIGURATION;
			if (districts == null || !oldLanguageCode.equals(configuration.languageCode()))
				refreshDistrictsTranslation();
			configurationLastModified = 0;
			districtsNotFound = Collections.emptyList();
			setLoggerLevel(configuration.logLevel());
			if (httpRequest == null || !oldTimeout.equals(configuration.timeout()))
				httpRequest = HttpRequest.newBuilder(URI.create("https://www.oref.org.il/WarningMessages/alert/alerts.json"))
						.header("Accept", "application/json")
//						.header("X-Requested-With", "XMLHttpRequest")
//						.header("Referer", "https://www.oref.org.il/12481-" + configuration.languageCode().name().toLowerCase(Locale.ROOT) + "/Pakar.aspx")
						.timeout(configuration.timeout())
						.build();
		}
	}

	private Map<Integer, AlertTranslation> loadAlertsTranslation() {
		LOGGER.info("Getting alerts translation from IDF's Home Front Command's server...");
		while (isContinue) {
			try {
				final Result<Map<Integer, AlertTranslation>> result = TimeMeasurement.measureAndExecuteCallable(() -> {
					final HttpResponse<InputStream> httpResponse = HTTP_CLIENT.send(
							HttpRequest.newBuilder(URI.create("https://www.oref.org.il/alerts/alertsTranslation.json"))
									.header("Accept", "application/json")
									.timeout(configuration.timeout())
									.build(),
							RESPONSE_BODY_HANDLER
					);
					try (InputStream body = httpResponse.body()) {
						if (httpResponse.statusCode() >= 200 && httpResponse.statusCode() < 300)
							return JSON_MAPPER.readValue(
											/*VAR_ALL_DISTRICTS.matcher(httpResponse.*/body/*()).replaceFirst("")*/,
											ALERTS_TRANSLATION_TYPE_REFERENCE
									)
									.parallelStream().unordered()
									.filter(alertTranslation -> alertTranslation.matrixCatId() != 0)
									.collect(Collectors.toConcurrentMap(
											AlertTranslation::matrixCatId,
											Function.identity(),
											(value1, value2) -> {
												LOGGER.trace("value1: {}, value2: {}", value1, value2);
												return value2;
											}
									));
					}
					LOGGER.error("Got bad response status code: {}", httpResponse.statusCode());
					return Collections.emptyMap();
				});
				if (result.getResult().isEmpty()) {
					sleep();
					continue;
				}
				LOGGER.info("Done (took {} milliseconds)", result.getTimeTaken());
				return result.getResult();
			} catch (JsonParseException e) {
				LOGGER.error("JSON parsing error: {}", e.toString());
			} catch (Exception e) {
				LOGGER.debug("Failed to get alerts translation for: {}. Trying again...", e.toString());
			}
			sleep();
		}
		return Collections.emptyMap();
	}

	private List<? extends IAreaTranslationProtectionTime> filterPrevAndGetTranslatedData(RedAlertEvent redAlertEvent,
																						  Map<Integer, Set<String>> prevData) {
		return redAlertEvent.data().parallelStream().unordered()
				.filter(Predicate.not(prevData.getOrDefault(redAlertEvent.cat(), Collections.emptySet())::contains))
				.map(key -> Option.of(districts.get(key)) instanceof Some(AreaTranslationProtectionTime areaTranslationProtectionTime) ?
						areaTranslationProtectionTime :
						new MissingTranslation(key))
				.toList();
	}

	@Override
	public void run() {
		System.err.println("Preparing " + getVersion()[0] + "...");
		try (Clip clip = AudioSystem.getClip(/*Stream.of(AudioSystem.getMixerInfo()).parallel().unordered()
				.filter(mixerInfo -> COLLATOR.equals(mixerInfo.getName(), "default [default]"))
				.findAny()
				.orElse(null)*/);
			 AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(new BufferedInputStream(Objects.requireNonNull(getClass().getResourceAsStream("/alarmSound.wav"))));
			 ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory())) {
			clip.open(audioInputStream);
			Thread.startVirtualThread(() -> {
				try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
					printHelpMsg();
					while (isContinue)
						switch (scanner.nextLine().strip()) {
							case "" -> {
							}
							case "q", "quit", "exit" -> isContinue = false;
							case "t", "test", "test-sound" -> {
								System.err.println("Testing sound...");
								clip.setFramePosition(0);
								clip.start();
							}
							case "c", "clear" -> System.err.println("\033[H\033[2JListening...");
							case "r", "refresh", "refresh-districts" -> refreshDistrictsTranslation();
							case "h", "help" -> printHelpMsg();
							case "l", "load-configuration" -> {
								try {
									loadConfiguration();
								} catch (IOException e) {
									LOGGER.info("Configuration error: {}", e.toString());
								}
							}
							default -> {
								System.err.println("Unrecognized command!");
								printHelpMsg();
							}
						}
				} catch (NoSuchElementException ignored) {
				}
				System.err.println("Bye Bye!");
			});
			scheduledExecutorService.scheduleAtFixedRate(this::refreshDistrictsTranslation, 1, 1, TimeUnit.DAYS);
			loadConfiguration();
			Map<Integer, AlertTranslation> alertsTranslation = loadAlertsTranslation();
			final Map<Integer, Set<String>> prevData = new HashMap<>(alertsTranslation.size());
			final var ref = new Object() {
				Instant currAlertsLastModified = Instant.MIN;
			};
			//language=JSON
			final long minRedAlertEventContentLength = """
					{"cat":"1","data":[],"desc":"","id":0,"title":""}""".getBytes(StandardCharsets.UTF_8).length;
			final Duration alarmSoundDuration = Duration.ofNanos(clip.getMicrosecondLength() * 1000);
			System.err.println("Listening...");
			while (isContinue)
				try {
//					loadConfiguration();
					final HttpResponse<InputStream> httpResponse = HTTP_CLIENT.send(
							httpRequest,
							RESPONSE_BODY_HANDLER
					);

					try (InputStream body = httpResponse.body()) {
						if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
							LOGGER.error("Connection response status code: {}", httpResponse.statusCode());
							sleep();
							continue;
						}
						switch (OptionLong.of(httpResponse.headers().firstValueAsLong("Content-Length"))) {
							case SomeLong(long contentLength) when contentLength > minRedAlertEventContentLength -> {
								//noinspection NestedSwitchStatement
								switch (Option.of(httpResponse.headers().firstValue("Last-Modified")
										.map(lastModifiedStr -> DateTimeFormatter.RFC_1123_DATE_TIME.parse(lastModifiedStr, Instant::from)))) {
									case Some(Instant lastModified) when ref.currAlertsLastModified.isBefore(lastModified) -> {
										ref.currAlertsLastModified = lastModified;

										final RedAlertEvent redAlertEvent = JSON_MAPPER.readValue(
												/*BOM.matcher(httpResponse.*/body/*()).replaceFirst("")*/,
												RedAlertEvent.class
										);
										LOGGER.debug("Original event data: {}", redAlertEvent);

										AlertTranslation alertTranslation = alertsTranslation.get(redAlertEvent.cat());
										if (alertTranslation == null) {
											LOGGER.warn("Couldn't find translation for cat: {} ({}), trying again...", redAlertEvent.cat(), redAlertEvent.title());
											alertsTranslation = loadAlertsTranslation();
											alertTranslation = alertsTranslation.get(redAlertEvent.cat());
										}
										final String
												title = alertTranslation == null ?
														redAlertEvent.title() + " (didn't find translation)" :
														alertTranslation.getAlertTitle(configuration.languageCode()),
												description = alertTranslation == null ?
														redAlertEvent.desc() + " (didn't find translation)" :
														alertTranslation.getAlertText(configuration.languageCode());

										//TODO rethink of what defines a drill alert
										if (redAlertEvent.data().parallelStream().unordered()
												.allMatch(LanguageCode.HE::containsTestKey)) {
											if (configuration.isShowTestAlerts())
												System.out.println(redAlertToString(
														contentLength,
														lastModified,
														title,
														description,
														redAlertEvent.data().parallelStream().unordered()
																.map(configuration.languageCode()::getTestTranslation)
																.sorted()
																.collect(Collectors.joining(
																		"," + System.lineSeparator() + "\t",
																		"Test Alert:" + System.lineSeparator() + "\t",
																		System.lineSeparator()
																)),
														new StringBuilder()
												));
											continue;
										}

										List<? extends IAreaTranslationProtectionTime> translatedData = filterPrevAndGetTranslatedData(redAlertEvent, prevData);

										boolean isContainsMissingTranslations = translatedData.parallelStream().unordered().anyMatch(MissingTranslation.class::isInstance);
										if (isContainsMissingTranslations) {
											LOGGER.warn("There is at least one district that couldn't be translated, refreshing districts translations from server...");
											refreshDistrictsTranslation();
											translatedData = filterPrevAndGetTranslatedData(redAlertEvent, prevData);
											//noinspection AssignmentUsedAsCondition
											if (isContainsMissingTranslations = translatedData.parallelStream().unordered().anyMatch(MissingTranslation.class::isInstance))
												LOGGER.warn("There is at least one district that couldn't be translated after districts refreshment");
										}

										final List<AreaTranslationProtectionTime> unseenTranslatedDistricts = translatedData.parallelStream().unordered()
												.distinct() //TODO think about
												.filter(AreaTranslationProtectionTime.class::isInstance)
												.map(AreaTranslationProtectionTime.class::cast)
												.toList(); //to know if new (unseen) districts were added from previous request.

										final StringBuilder output = new StringBuilder();
										if (configuration.isDisplayResponse() && !unseenTranslatedDistricts.isEmpty())
											redAlertToString(
													contentLength,
													lastModified,
													title,
													description,
													areaAndTranslatedDistrictsToString("Translated Areas and Districts", unseenTranslatedDistricts, redAlertEvent.cat()),
													output
											);
										if (configuration.isDisplayUntranslatedDistricts() && isContainsMissingTranslations) {
											output.append(translatedData.parallelStream().unordered()
													.distinct() //TODO think about
													.filter(MissingTranslation.class::isInstance)
													.map(MissingTranslation.class::cast)
													.map(MissingTranslation::untranslatedName)
													.sorted()
													.collect(Collectors.joining(
															"," + System.lineSeparator() + "\t",
															"Untranslated Districts:" + System.lineSeparator() + "\t",
															System.lineSeparator()
													)));
										}

										final List<AreaTranslationProtectionTime> districtsForAlert = unseenTranslatedDistricts.parallelStream().unordered()
												.filter(translationAndProtectionTime -> configuration.districtsOfInterest().contains(translationAndProtectionTime.translation()))
												.toList(); //for not restarting alert sound unnecessarily
										if (Option.of((configuration.isAlertAll() ? unseenTranslatedDistricts : districtsForAlert).parallelStream().unordered()
												.map(AreaTranslationProtectionTime::protectionTime)
												.min(Comparator.naturalOrder())) instanceof Some(Duration minProtectionTime)) {
											if (configuration.isMakeSound()) {
												clip.setFramePosition(0);
												//noinspection NumericCastThatLosesPrecision
												clip.loop(Math.max(1, (int) minProtectionTime.dividedBy(alarmSoundDuration)));
											}
											output.append(areaAndTranslatedDistrictsToString("ALERT ALERT ALERT", districtsForAlert, redAlertEvent.cat()));
										}

										if (!output.isEmpty())
											System.out.println(output);

										printDistrictsNotFoundWarning();
										prevData.put(redAlertEvent.cat(), new HashSet<>(redAlertEvent.data()));
									}
									case Some<Instant> ignored -> {
									}
									case None<Instant> ignored -> LOGGER.error("Couldn't get last modified date");
								}
							}
							case SomeLong ignored -> prevData.clear();
							case NoneLong ignored -> {
								LOGGER.error("Couldn't get content length");
								prevData.clear();
							}
						}
					}
				} catch (JsonParseException e) {
					LOGGER.error("JSON parsing error: {}", e.toString());
				} catch (IOException e) {
					if (Option.of(e.getMessage()) instanceof Some(String message) && message.contains("GOAWAY received"))
						LOGGER.trace("Got GOAWAY: {}", e.toString());
					else
						LOGGER.debug("Got exception: {}", e.toString());
					sleep();
				}
		} catch (Throwable e) {
			LOGGER.fatal("Closing connection and exiting...", e);
		}
	}

	private void refreshDistrictsTranslation() {
		final Map<String, AreaTranslationProtectionTime> updatedDistricts = loadRemoteDistricts(
				configuration.languageCode(),
				configuration.timeout(),
				district -> new AreaTranslationProtectionTime(
						district.areaname(),
						district.label(),
						district.migun_time()
				)
		);
		if (LOGGER.isDebugEnabled()) {
			final Map<String, AreaTranslationProtectionTime> newAndModifiedDistricts = updatedDistricts.entrySet().parallelStream().unordered()
					.filter(Predicate.not(districts.entrySet()::contains))
					.collect(Collectors.toConcurrentMap(Map.Entry::getKey, Map.Entry::getValue));
			if (!newAndModifiedDistricts.isEmpty())
				LOGGER.debug("New or modified districts: {}", newAndModifiedDistricts);
			final Map<String, AreaTranslationProtectionTime> deletedDistricts = districts.entrySet().parallelStream().unordered()
					.filter(Predicate.not(updatedDistricts.entrySet()::contains))
					.collect(Collectors.toConcurrentMap(Map.Entry::getKey, Map.Entry::getValue));
			if (!deletedDistricts.isEmpty())
				LOGGER.debug("Deleted districts: {}", deletedDistricts);
		}
		districts = updatedDistricts;
	}

	private static class LoggerLevelConverter implements CommandLine.ITypeConverter<Level> {
		@Override
		public Level convert(String value) {
			return Level.valueOf(value);
		}
	}
}
