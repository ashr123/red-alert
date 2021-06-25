package il.co;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sound.sampled.*;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@SuppressWarnings({"StringBufferReplaceableByString", "InfiniteLoopStatement"})
public class RedAlert
{

	private static Settings settings = null;
	private static long settingsLastModified = 0;
	private static Set<String> districtsNotFound = Collections.emptySet();
	private static HttpURLConnection httpURLConnectionField;

	public static void main(String... args) throws IOException, UnsupportedAudioFileException, LineUnavailableException
	{
		System.err.println("Preparing Red Alert listener, enter \"t\" for sound test or \"q\" to quit...");
		try (Clip clip = AudioSystem.getClip();
		     AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(new BufferedInputStream(Objects.requireNonNull(RedAlert.class.getResourceAsStream("/alarmSound.wav")))))
		{
			clip.open(audioInputStream);
			new Thread(() ->
			{
				final Scanner scanner = new Scanner(System.in);
				while (true)
					switch (scanner.nextLine())
					{
						case "q", "exit", "quit" -> {
							System.err.println("Bye Bye!");
							try (clip; audioInputStream)
							{
								httpURLConnectionField.disconnect();
							} catch (IOException e)
							{
								e.printStackTrace();
							}
							System.exit(0);
						}
						case "t", "test", "test sound", "test-sound" -> {
							System.err.println("Testing sound...");
							clip.setFramePosition(0);
							clip.start();
						}
					}
			}).start();
			final URL url = new URL("https://www.oref.org.il/WarningMessages/alert/alerts.json");
			final ObjectMapper objectMapper = new ObjectMapper();
			final SimpleDateFormat SIMPLE_DATE_FORMAT = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
			final File settingsFile = new File("red-alert-settings.json");
			Set<String> prevData = Collections.emptySet();
			loadSettings(objectMapper, settingsFile);
			long currAlertsLastModified = 0;
			System.err.println("Listening...");
			while (true)
				try
				{
					if (url.openConnection() instanceof HttpURLConnection httpURLConnection)
					{
						loadSettings(objectMapper, settingsFile);
						httpURLConnectionField = httpURLConnection;
						httpURLConnection.setRequestProperty("Referer", "https://www.oref.org.il/12481-" + settings.language().name().toLowerCase() + "/Pakar.aspx");
						httpURLConnection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
						httpURLConnection.setConnectTimeout(settings.connectTimeout());
						httpURLConnection.setReadTimeout(settings.readTimeout());
						httpURLConnection.setUseCaches(false);

						if (httpURLConnection.getResponseCode() != HttpURLConnection.HTTP_OK)
						{
							System.err.println("Error at " + new Date() + ": connection " + httpURLConnection.getResponseMessage());
							sleep();
							continue;
						}
						Date alertsLastModified = null;
						final long contentLength = httpURLConnection.getContentLengthLong();
						final String lastModifiedStr;
						if (contentLength == 0)
							prevData = Collections.emptySet();
						else if ((lastModifiedStr = httpURLConnection.getHeaderField("last-modified")) == null ||
								(alertsLastModified = SIMPLE_DATE_FORMAT.parse(lastModifiedStr)).getTime() > currAlertsLastModified)
						{
							if (alertsLastModified != null)
								currAlertsLastModified = alertsLastModified.getTime();

							final Set<String> data = objectMapper.readValue(httpURLConnection.getInputStream(), RedAlertResponse.class).data();

							if (settings == null || settings.isDisplayAll())
							{
								System.out.println(new StringBuilder("Content Length: ").append(contentLength).append(" bytes").append(System.lineSeparator())
										.append("Last Modified Date: ").append(alertsLastModified).append(System.lineSeparator())
										.append("Current Date: ").append(new Date()).append(System.lineSeparator())
										.append("Response: ").append(data));
							}

							printDistrictsNotFoundWarning();
							if (settings != null)
							{
								final Set<String> importantDistricts = (data.size() > settings.districtsOfInterest().size() ?
										data.parallelStream()
												.filter(settings.districtsOfInterest()::contains) :
										settings.districtsOfInterest().parallelStream()
												.filter(data::contains))
										.filter(Predicate.not(prevData::contains))
										.collect(Collectors.toSet());
								prevData = data;
								if (settings.isMakeSound() && (settings.isAlertAll() || !importantDistricts.isEmpty()))
								{
									clip.setFramePosition(0);
									clip.loop(settings.soundLoopCount());
								}
								if (!importantDistricts.isEmpty())
									System.out.println("ALERT: " + importantDistricts);
							} else
								System.err.println("Warning: Settings file doesn't exists!");
						}
					} else
						System.err.println("Error at " + new Date() + ": Not a HTTP connection!");
				} catch (IOException | ParseException e)
				{
					System.err.println("Exception at " + new Date() + ": " + e);
					if (e instanceof IOException)
						sleep();
				}
		}
	}

	private static void printDistrictsNotFoundWarning()
	{
		if (!districtsNotFound.isEmpty())
			System.err.println("Warning: those districts don't exist: " + districtsNotFound);
	}

	private static void sleep()
	{
		try
		{
			Thread.sleep(1000);
		} catch (InterruptedException interruptedException)
		{
			interruptedException.printStackTrace();
		}
	}

	private static void loadSettings(ObjectMapper objectMapper, File settingsFile) throws IOException
	{
		final long settingsLastModifiedTemp = settingsFile.lastModified();
		if (settingsLastModifiedTemp > settingsLastModified)
		{
			System.err.println("Info: (re)loading settings from file \"red-alert-settings.json\"");
			settings = objectMapper.readValue(settingsFile, Settings.class);
			settingsLastModified = settingsLastModifiedTemp;
			districtsNotFound = settings.districtsOfInterest().parallelStream()
					.filter(Predicate.not(settings.language().getDistricts()::contains))
					.collect(Collectors.toSet());
			printDistrictsNotFoundWarning();
		} else if (settingsLastModifiedTemp == 0)
		{
			settings = null;
			settingsLastModified = 0;
			districtsNotFound = Collections.emptySet();
		}
	}

	public static final record RedAlertResponse(Set<String> data, long id, String title)
	{
	}

	public static final record Settings(boolean isMakeSound,
	                                    boolean isAlertAll,
	                                    boolean isDisplayAll,
	                                    int connectTimeout,
	                                    int readTimeout,
	                                    int soundLoopCount,
	                                    LanguageUtil language,
	                                    Set<String> districtsOfInterest)
	{
	}
}