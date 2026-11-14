package uz.jarvis.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.telephony.SmsManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Jarvisning "miyasi": API kalitsiz ishlaydi.
 * Buyruqlarni kalit so'zlar orqali tushunadi, internetdan esa
 * kalit talab qilmaydigan ochiq manbalardan foydalanadi (wttr.in, Vikipediya).
 */
final class CommandEngine {

    interface Host {
        void say(String text);
        /** Gapiradi va javobni eshitish uchun mikrofonni o'zi yoqadi. */
        void ask(String text);
        void status(String text);
        String lang();
        void stopSpeaking();
        void requestPerms(String[] perms, String retryCommand);
    }

    private final Activity act;
    private final Host host;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private String lastCommand = "";

    // Ko'p bosqichli suhbat holati (masalan, SMS: kimga -> nima deb -> tasdiq)
    private static final int NONE = 0, SMS_TO = 1, SMS_TEXT = 2, SMS_CONFIRM = 3;
    private int pending = NONE;
    private String smsTo, smsNumber, smsText;

    /** AI suhbat tarixi: {rol, matn}. */
    private final List<String[]> history = new ArrayList<>();

    private static final String[] UZ_DAYS = {"yakshanba", "dushanba", "seshanba", "chorshanba", "payshanba", "juma", "shanba"};
    private static final String[] UZ_MONTHS = {"yanvar", "fevral", "mart", "aprel", "may", "iyun", "iyul", "avgust", "sentabr", "oktabr", "noyabr", "dekabr"};

    /** O'zbekcha ilova nomlari -> telefonda uchraydigan inglizcha nomlar. */
    private static final Map<String, String[]> APP_ALIASES = new HashMap<>();
    static {
        APP_ALIASES.put("kamera", new String[]{"camera", "kamera"});
        APP_ALIASES.put("galereya", new String[]{"gallery", "photos", "galereya", "rasmlar"});
        APP_ALIASES.put("rasmlar", new String[]{"gallery", "photos", "galereya"});
        APP_ALIASES.put("soat", new String[]{"clock", "soat"});
        APP_ALIASES.put("kalkulyator", new String[]{"calculator", "kalkulyator"});
        APP_ALIASES.put("xarita", new String[]{"maps", "xarita", "yandex maps"});
        APP_ALIASES.put("brauzer", new String[]{"chrome", "browser", "brauzer"});
        APP_ALIASES.put("kontaktlar", new String[]{"contacts", "kontaktlar"});
        APP_ALIASES.put("xabarlar", new String[]{"messages", "xabarlar"});
        APP_ALIASES.put("sms", new String[]{"messages", "xabarlar"});
        APP_ALIASES.put("musiqa", new String[]{"music", "musiqa", "spotify"});
        APP_ALIASES.put("kalendar", new String[]{"calendar", "kalendar", "taqvim"});
        APP_ALIASES.put("taqvim", new String[]{"calendar", "taqvim"});
        APP_ALIASES.put("pochta", new String[]{"gmail", "email", "pochta"});
        APP_ALIASES.put("telefon", new String[]{"phone", "telefon"});
        APP_ALIASES.put("fayllar", new String[]{"files", "file manager", "fayllar"});
        APP_ALIASES.put("telegram", new String[]{"telegram"});
        APP_ALIASES.put("instagram", new String[]{"instagram"});
        APP_ALIASES.put("yutub", new String[]{"youtube"});
    }

    private static final Set<String> SETTINGS_WORDS = new HashSet<>(Arrays.asList(
            "sozlamalar", "sozlama", "settings", "nastroyki", "sozlamalarni"));

    CommandEngine(Activity act, Host host) {
        this.act = act;
        this.host = host;
        this.prefs = act.getSharedPreferences("jarvis", Context.MODE_PRIVATE);
    }

    void shutdown() {
        exec.shutdownNow();
    }

    private boolean uz() {
        return "uz".equals(host.lang());
    }

    private String t(String uz, String en) {
        return uz() ? uz : en;
    }

    private void say(String s) {
        host.say(s);
    }

    // ================================================================ main router

    void handle(String raw) {
        handleInternal(raw, true);
    }

    private void handleInternal(String raw, boolean allowAi) {
        lastCommand = raw;
        String s = norm(raw);
        if (pending != NONE && handlePending(raw, s)) return;
        if (s.isEmpty()) {
            say(t("Ha, Sir? Buyuring.", "Yes, Sir?"));
            return;
        }
        List<String> w = Arrays.asList(s.split(" "));

        if (hasWord(w, "jim", "stop") || has(s, "to'xta", "shut up")) {
            host.stopSpeaking();
            return;
        }
        if (hasWord(w, "xayr", "goodbye", "bye")) {
            say(t("Xayr, Sir. Kerak bo'lsam, chaqiring.", "Goodbye, Sir."));
            main.postDelayed(() -> act.moveTaskToBack(true), 2500);
            return;
        }
        if (has(s, "nima qila olasan", "nimalar qila olasan", "yordam", "help", "what can you do", "buyruqlar")) {
            help();
            return;
        }
        if (w.size() <= 2 && has(s, "rahmat", "thank", "tashakkur")) {
            say(t("Arzimaydi, Sir. Doim xizmatingizdaman.", "You're welcome, Sir."));
            return;
        }
        if (w.size() <= 2 && has(s, "salom", "assalom", "hello", "hey", "qalaysan", "how are you")) {
            say(t("Salom, Sir! Barcha tizimlar ishlayapti. Nima qilamiz?",
                    "Hello, Sir! All systems are online. What shall we do?"));
            return;
        }

        // ---- xotira
        if (has(s, "eslab qolding", "eslab qolgan", "esingda nima", "nimalarni bilasan", "what do you remember")) { recallNotes(); return; }
        if (has(s, "hammasini unut", "xotirani tozala", "forget everything")) { forgetNotes(); return; }
        if (has(s, "ismim nima", "men kimman", "what's my name", "whats my name", "what is my name")) { tellName(); return; }
        if (has(s, "mening ismim", "my name is") || s.startsWith("ismim ")) { setName(raw); return; }
        if (has(s, "eslab qol", "yodda tut", "esingda tut", "remember that", "remember ")) { rememberNote(raw); return; }

        // ---- SMS
        if (hasWord(w, "sms", "smska") || has(s, "xabar yoz", "xabar yubor", "xabar jo'nat", "sms yoz", "message to", "text to")
                || s.matches(".*\\S+(?:ga|ka|qa) .* deb xabar.*")) {
            smsStart(raw);
            return;
        }

        if (has(s, "budilnik", "uyg'ot", "alarm")) { alarm(s); return; }
        if (has(s, "taymer", "timer")) { timer(s); return; }
        if (has(s, "soat nech", "soat qancha", "vaqt qancha", "hozir vaqt", "what time", "the time")) { time(); return; }
        if (has(s, "bugun nechan", "qaysi sana", "bugungi sana", "sana nechi", "bugun qaysi", "what day", "the date", "today's date")) { date(); return; }
        if (has(s, "batareya", "zaryad", "battery")) { battery(); return; }
        if (has(s, "fonar", "chiroq", "flashlight", "torch")) {
            flashlight(!has(s, "o'chir", "off", "o'chi"));
            return;
        }
        if (has(s, "wifi", "wi-fi", "wi fi", "vay fay", "vayfay", "vay-fay")) { wifi(); return; }
        if (has(s, "bluetooth", "blutuz", "blyutuz")) {
            open(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS), t("Bluetooth sozlamalarini ochdim, Sir.", "Opened Bluetooth settings, Sir."));
            return;
        }
        if (has(s, "mobil internet", "internetni yoq", "internetni o'chir", "mobile data", "mobil ma'lumot", "internetni och")) { internetPanel(); return; }
        if (has(s, "ovozni", "tovush", "volume", "ovoz baland", "ovoz past")) { volume(s); return; }
        if (has(s, "qo'ng'iroq", "qong'iroq", "telefon qil", "call ", "zvonit")) { call(s); return; }
        if (has(s, "ob-havo", "ob havo", "obhavo", "havo qanday", "weather")) { weather(w); return; }
        if (has(s, "youtube", "yutub", "yutyub", "youtub")) { youtube(s); return; }
        if (has(s, "yo'l ko'rsat", "qanday boraman", "xaritada", "navigate", "directions to", "show on map")) { maps(s); return; }
        if (hasWord(w, "qidir", "qidirib", "izla", "izlab", "google", "googleda", "google'da", "googlda", "search")) {
            search(stripWords(s, "qidir", "qidirib", "izla", "izlab", "ber", "google", "googleda", "google'da", "googlda", "internetdan",
                    "search", "for", "on", "da", "menga"));
            return;
        }
        if (s.matches(".*\\d.*") && tryMath(s)) return;
        if (hasWord(w, "och", "ochib", "ochgin", "oching", "ochchi", "open", "launch") || has(s, "ishga tushir")) { openApp(s); return; }
        if (has(s, "vikipediya", "wikipedia", "vikipediyadan")) { knowledge(s); return; }

        if (allowAi) {
            askAi(raw);
        } else {
            say(t("Buni bajarishni hali bilmayman, Sir.", "I don't know how to do that yet, Sir."));
        }
    }

    // ================================================================ text helpers

    private static String norm(String raw) {
        String s = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[ʻʼ’‘`´]", "'")
                .replaceAll("[,!?;\"«»()]", " ")
                .replaceAll("\\.(\\s|$)", " ")
                .replaceAll("\\b(jarvis|jarvisjon|sir|ser)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return s;
    }

    private static boolean has(String s, String... keys) {
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    private static boolean hasWord(List<String> words, String... keys) {
        for (String k : keys) if (words.contains(k)) return true;
        return false;
    }

    private static String stripWords(String s, String... remove) {
        Set<String> rm = new HashSet<>(Arrays.asList(remove));
        StringBuilder sb = new StringBuilder();
        for (String word : s.split(" ")) {
            if (word.isEmpty() || rm.contains(word)) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(word);
        }
        return sb.toString().trim();
    }

    private static String stripSuffix(String s, String... suffixes) {
        for (String suf : suffixes) {
            if (s.endsWith(suf) && s.length() > suf.length() + 2) return s.substring(0, s.length() - suf.length());
        }
        return s;
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void open(Intent i, String reply) {
        main.post(() -> {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(i);
                if (reply != null) say(reply);
            } catch (ActivityNotFoundException e) {
                say(t("Bu amal uchun telefoningizda ilova topilmadi, Sir.", "No app on your phone can do that, Sir."));
            } catch (SecurityException e) {
                say(t("Telefon bu amalga ruxsat bermadi, Sir.", "The system blocked that action, Sir."));
            }
        });
    }

    // ================================================================ commands

    private void help() {
        say(t("Men quyidagilarni qila olaman, Sir: ilovalarni ochish, fonar, soat va sana, budilnik va taymer, "
                        + "qo'ng'iroq va SMS (Aliga sms yoz salom), ob-havo, Google va YouTube'da qidirish, hisob-kitob, "
                        + "ovoz balandligi, Wi-Fi va Bluetooth. Narsalarni eslab qolaman (eslab qol, ...). "
                        + "Va istalgan mavzuda erkin suhbatlasha olaman. Shunchaki oddiy tilda gapiring.",
                "I can open apps, toggle the flashlight, tell time and date, set alarms and timers, call and text people, "
                        + "check the weather, search Google and YouTube, do math, change volume, remember things, "
                        + "and chat freely about anything, Sir. Just talk to me naturally."));
    }

    private void time() {
        Calendar c = Calendar.getInstance();
        String hm = String.format(Locale.ROOT, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        say(t("Hozir soat " + hm + ", Sir.", "It's " + hm + ", Sir."));
    }

    private void date() {
        Calendar c = Calendar.getInstance();
        if (uz()) {
            say("Bugun " + c.get(Calendar.DAY_OF_MONTH) + "-" + UZ_MONTHS[c.get(Calendar.MONTH)] + ", "
                    + UZ_DAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + ", Sir.");
        } else {
            say("Today is " + new SimpleDateFormat("EEEE, MMMM d", Locale.US).format(c.getTime()) + ", Sir.");
        }
    }

    private void battery() {
        BatteryManager bm = (BatteryManager) act.getSystemService(Context.BATTERY_SERVICE);
        int pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        String extra = pct < 20 ? t(" Quvvatlashni maslahat beraman.", " I'd recommend charging.") : "";
        say(t("Batareya " + pct + " foiz, Sir." + extra, "Battery is at " + pct + " percent, Sir." + extra));
    }

    private void flashlight(boolean on) {
        try {
            CameraManager cm = (CameraManager) act.getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                Boolean hasFlash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (hasFlash != null && hasFlash) {
                    cm.setTorchMode(id, on);
                    say(on ? t("Fonar yoqildi, Sir.", "Flashlight on, Sir.") : t("Fonar o'chirildi, Sir.", "Flashlight off, Sir."));
                    return;
                }
            }
            say(t("Telefoningizda chiroq topilmadi, Sir.", "No flashlight found, Sir."));
        } catch (Exception e) {
            say(t("Fonarni boshqara olmadim, Sir. Kamera band bo'lishi mumkin.", "Couldn't control the flashlight, Sir."));
        }
    }

    private void wifi() {
        Intent i = Build.VERSION.SDK_INT >= 29
                ? new Intent(Settings.Panel.ACTION_WIFI)
                : new Intent(Settings.ACTION_WIFI_SETTINGS);
        open(i, t("Wi-Fi panelini ochdim, Sir. Android xavfsizlik sababli uni o'zim almashtirishimga ruxsat bermaydi, bir marta bosing.",
                "Here's the Wi-Fi panel, Sir. Android doesn't let apps toggle it directly."));
    }

    private void internetPanel() {
        Intent i = Build.VERSION.SDK_INT >= 29
                ? new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                : new Intent(Settings.ACTION_WIRELESS_SETTINGS);
        open(i, t("Internet panelini ochdim, Sir.", "Opened the internet panel, Sir."));
    }

    private void volume(String s) {
        AudioManager am = (AudioManager) act.getSystemService(Context.AUDIO_SERVICE);
        int stream = AudioManager.STREAM_MUSIC;
        if (has(s, "o'chir", "mute", "jim")) {
            am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI);
            say(t("Ovoz o'chirildi, Sir.", "Muted, Sir."));
        } else if (has(s, "maksimal", "eng baland", "max")) {
            am.setStreamVolume(stream, am.getStreamMaxVolume(stream), AudioManager.FLAG_SHOW_UI);
            say(t("Ovoz maksimal, Sir.", "Volume at maximum, Sir."));
        } else if (has(s, "past", "kamaytir", "pasaytir", "down", "quieter", "lower")) {
            for (int i = 0; i < 3; i++) am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, i == 0 ? AudioManager.FLAG_SHOW_UI : 0);
            say(t("Ovozni pasaytirdim, Sir.", "Volume down, Sir."));
        } else {
            am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0);
            for (int i = 0; i < 3; i++) am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, i == 0 ? AudioManager.FLAG_SHOW_UI : 0);
            say(t("Ovozni balandlatdim, Sir.", "Volume up, Sir."));
        }
    }

    private void alarm(String s) {
        Matcher m = Pattern.compile("(\\d{1,2})(?:[:.\\s](\\d{2}))?").matcher(s);
        if (!m.find()) {
            say(t("Soat nechaga, Sir? Masalan: soat 7 da budilnik qo'y.", "What time, Sir? For example: set an alarm for 7."));
            return;
        }
        int h = Integer.parseInt(m.group(1));
        int min = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
        if (has(s, "kechki", "kechqurun", "kechasi", "pm", "tushdan keyin") && h < 12) h += 12;
        if (h > 23 || min > 59) {
            say(t("Vaqtni tushunmadim, Sir.", "I didn't get the time, Sir."));
            return;
        }
        String hm = String.format(Locale.ROOT, "%02d:%02d", h, min);
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, h)
                .putExtra(AlarmClock.EXTRA_MINUTES, min)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        open(i, t("Budilnik soat " + hm + " ga qo'yildi, Sir.", "Alarm set for " + hm + ", Sir."));
    }

    private void timer(String s) {
        int seconds = 0;
        if (has(s, "yarim soat", "half an hour")) {
            seconds = 1800;
        } else {
            Matcher m = Pattern.compile("(\\d+)\\s*(daqiqa|minut|minute|min|soniya|sekund|second|sec|soat|hour)?").matcher(s);
            if (m.find()) {
                int n = Integer.parseInt(m.group(1));
                String unit = m.group(2) == null ? "daqiqa" : m.group(2);
                if (unit.startsWith("soniya") || unit.startsWith("sekund") || unit.startsWith("sec")) seconds = n;
                else if (unit.startsWith("soat") || unit.startsWith("hour")) seconds = n * 3600;
                else seconds = n * 60;
            }
        }
        if (seconds <= 0 || seconds > 86400) {
            say(t("Necha daqiqaga, Sir? Masalan: 5 daqiqaga taymer qo'y.", "For how long, Sir? For example: timer for 5 minutes."));
            return;
        }
        Intent i = new Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        String human = seconds % 60 == 0
                ? (seconds / 60) + t(" daqiqa", " minutes")
                : seconds + t(" soniya", " seconds");
        open(i, t("Taymer " + human + "ga qo'yildi, Sir.", "Timer set for " + human + ", Sir."));
    }

    private void call(String s) {
        String who = stripWords(s, "qo'ng'iroq", "qong'iroq", "qil", "qiling", "qilgin", "qilib", "ber", "telefon",
                "call", "please", "iltimos", "menga", "zvonit", "uchun");
        who = stripSuffix(who, "ga", "ka", "qa");
        if (who.isEmpty()) {
            say(t("Kimga qo'ng'iroq qilay, Sir?", "Who should I call, Sir?"));
            return;
        }
        String digits = who.replaceAll("[^0-9+]", "");
        String number;
        String display;
        if (digits.replace("+", "").length() >= 5) {
            number = digits;
            display = digits;
        } else {
            if (act.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                say(t("Kontaktlar va qo'ng'iroqqa ruxsat bering, Sir, keyin darhol qayta urinaman.",
                        "Please allow contacts and phone access, Sir. I'll retry right after."));
                host.requestPerms(new String[]{Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE}, lastCommand);
                return;
            }
            String[] found = findContact(who);
            if (found == null) {
                say(t("Kontaktlarda \"" + who + "\" topilmadi, Sir.", "I couldn't find \"" + who + "\" in contacts, Sir."));
                return;
            }
            display = found[0];
            number = found[1];
        }
        boolean canCall = act.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED;
        Intent i = new Intent(canCall ? Intent.ACTION_CALL : Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)));
        open(i, t(display + "ga qo'ng'iroq qilyapman, Sir.", "Calling " + display + ", Sir."));
    }

    private String[] findContact(String name) {
        String col = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME;
        try (Cursor c = act.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{col, ContactsContract.CommonDataKinds.Phone.NUMBER},
                col + " LIKE ?", new String[]{"%" + name + "%"}, null)) {
            if (c != null && c.moveToFirst()) return new String[]{c.getString(0), c.getString(1)};
        } catch (Exception ignored) {
        }
        return null;
    }

    private void weather(List<String> words) {
        Set<String> skip = new HashSet<>(Arrays.asList("ob-havo", "ob", "havo", "obhavo", "qanday", "bugun", "hozir",
                "ertaga", "weather", "in", "the", "what's", "whats", "is", "what", "like", "today", "how", "haqida",
                "ayt", "aytib", "ber", "menga"));
        String city = "";
        for (int k = 0; k < words.size(); k++) {
            String word = words.get(k);
            if (word.equals("in") && k + 1 < words.size()) {
                city = words.get(k + 1);
                break;
            }
            if (skip.contains(word)) continue;
            if (word.endsWith("dagi") && word.length() > 5) { city = word.substring(0, word.length() - 4); break; }
            if (word.endsWith("da") && word.length() > 3) { city = word.substring(0, word.length() - 2); break; }
        }
        if (city.isEmpty()) city = uz() ? "toshkent" : "tashkent";
        final String place = city;
        say(t("Ob-havoni tekshiryapman, Sir...", "Checking the weather, Sir..."));
        exec.execute(() -> {
            try {
                String url = "https://wttr.in/" + URLEncoder.encode(place, "UTF-8")
                        + "?m&lang=" + (uz() ? "uz" : "en")
                        + "&format=" + URLEncoder.encode("%C|%t|%f|%w", "UTF-8");
                String[] p = httpGet(url).trim().split("\\|");
                if (p.length < 4) throw new IllegalStateException("format");
                String c = capitalize(place);
                say(t(c + "da hozir " + p[1].trim() + ", " + p[0].trim().toLowerCase(Locale.ROOT)
                                + ". Sezilishi " + p[2].trim() + ", shamol " + p[3].trim() + ", Sir.",
                        "In " + c + " it's " + p[1].trim() + ", " + p[0].trim().toLowerCase(Locale.ROOT)
                                + ". Feels like " + p[2].trim() + ", wind " + p[3].trim() + ", Sir."));
            } catch (Exception e) {
                say(t("Ob-havo ma'lumotini ololmadim, Sir. Internetni tekshiring.", "Couldn't get the weather, Sir. Check the internet."));
            }
        });
    }

    private void youtube(String s) {
        String q = stripWords(s, "youtube", "youtube'da", "youtube'dan", "youtube'ni", "youtubeda", "youtubedan", "youtubeni", "yutub", "yutubda", "yutubdan", "yutyub",
                "youtub", "youtubda", "och", "ochib", "ber", "qo'y", "qo'yib", "ko'rsat", "top", "izla", "qidir", "play", "on", "open", "search", "for");
        if (q.isEmpty()) {
            launchByName("youtube");
            return;
        }
        Intent app = new Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q);
        try {
            app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(app);
            say(t("YouTube'da \"" + q + "\" ni qidiryapman, Sir.", "Searching YouTube for \"" + q + "\", Sir."));
        } catch (Exception e) {
            open(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))),
                    t("YouTube'da qidiryapman, Sir.", "Searching YouTube, Sir."));
        }
    }

    private void maps(String s) {
        String place = stripWords(s, "yo'l", "ko'rsat", "qanday", "boraman", "xaritada", "navigate", "directions", "to",
                "show", "on", "map", "ga", "gacha", "menga", "ber");
        place = stripSuffix(place, "gacha", "ga", "ka", "qa");
        if (place.isEmpty()) {
            say(t("Qayerga, Sir?", "Where to, Sir?"));
            return;
        }
        open(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))),
                t("Xaritada \"" + place + "\" ni ochdim, Sir.", "Showing \"" + place + "\" on the map, Sir."));
    }

    private void search(String q) {
        if (q.isEmpty()) {
            say(t("Nimani qidiray, Sir?", "What should I search for, Sir?"));
            return;
        }
        open(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))),
                t("Google'da \"" + q + "\" ni qidiryapman, Sir.", "Searching Google for \"" + q + "\", Sir."));
    }

    private boolean tryMath(String s) {
        String e = " " + s + " ";
        String[][] words = {
                {"qo'shuv", "+"}, {"qo'shilsa", "+"}, {"qo'sh", "+"}, {"plyus", "+"}, {"plus", "+"},
                {"ayiruv", "-"}, {"ayirilsa", "-"}, {"ayir", "-"}, {"minus", "-"},
                {"ko'paytiruv", "*"}, {"ko'paytirilsa", "*"}, {"ko'paytir", "*"}, {"karra", "*"}, {"times", "*"}, {"multiplied by", "*"},
                {"bo'lingan", "/"}, {"bo'linsa", "/"}, {"bo'lish", "/"}, {"bo'l", "/"}, {"divided by", "/"}, {"÷", "/"}
        };
        for (String[] p : words) e = e.replace(" " + p[0] + " ", " " + p[1] + " ");
        e = e.replaceAll("(?<=\\d)\\s*[x×]\\s*(?=\\d)", "*").replace(',', '.');
        if (!e.matches(".*\\d\\s*[-+*/]\\s*[\\d(].*")) return false;
        String expr = e.replaceAll("[^0-9+\\-*/().\\s]", " ").trim();
        try {
            double v = MathEval.eval(expr);
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                say(t("Nolga bo'lib bo'lmaydi, Sir.", "Can't divide by zero, Sir."));
                return true;
            }
            String out = (v == Math.rint(v) && Math.abs(v) < 1e15)
                    ? String.valueOf((long) v)
                    : String.format(Locale.ROOT, "%.4f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
            say(t("Javob: " + out + ", Sir.", "The answer is " + out + ", Sir."));
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    // ---------------------------------------------------------------- apps

    private void openApp(String s) {
        String name = stripWords(s, "och", "ochib", "ochgin", "oching", "ochchi", "ber", "bering", "ishga", "tushir",
                "tushiring", "ilovasini", "ilovani", "ilova", "dasturini", "dasturni", "app", "the", "open", "launch",
                "please", "iltimos", "menga", "start");
        name = stripSuffix(name, "ni");
        if (name.isEmpty()) {
            say(t("Qaysi ilovani ochay, Sir?", "Which app, Sir?"));
            return;
        }
        if (SETTINGS_WORDS.contains(name)) {
            open(new Intent(Settings.ACTION_SETTINGS), t("Sozlamalarni ochdim, Sir.", "Opened settings, Sir."));
            return;
        }
        if (name.equals("kamera") || name.equals("camera")) {
            open(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), t("Kamera ochildi, Sir.", "Camera is open, Sir."));
            return;
        }
        if (launchByName(name)) return;
        String[] alias = APP_ALIASES.get(name);
        if (alias != null) {
            for (String a : alias) if (launchByName(a)) return;
        }
        say(t("\"" + name + "\" degan ilova topilmadi, Sir.", "I couldn't find an app called \"" + name + "\", Sir."));
    }

    private static String simplify(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private boolean launchByName(String name) {
        String n = simplify(name);
        if (n.length() < 2) return false;
        PackageManager pm = act.getPackageManager();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(mainIntent, 0);
        ResolveInfo best = null;
        int bestScore = 0;
        for (ResolveInfo ri : apps) {
            if (act.getPackageName().equals(ri.activityInfo.packageName)) continue;
            String label = simplify(ri.loadLabel(pm).toString());
            if (label.isEmpty()) continue;
            int score = 0;
            if (label.equals(n)) score = 100;
            else if (label.startsWith(n)) score = 80;
            else if (n.startsWith(label) && label.length() >= 3) score = 70;
            else if (label.contains(n)) score = 50;
            else if (n.contains(label) && label.length() >= 4) score = 40;
            if (score > bestScore) {
                bestScore = score;
                best = ri;
            }
        }
        if (best == null) return false;
        Intent launch = pm.getLaunchIntentForPackage(best.activityInfo.packageName);
        if (launch == null) return false;
        open(launch, t(best.loadLabel(pm) + " ochilmoqda, Sir.", "Opening " + best.loadLabel(pm) + ", Sir."));
        return true;
    }

    // ---------------------------------------------------------------- knowledge (Wikipedia)

    private void knowledge(String s) {
        final String topic = stripWords(s, "nima", "kim", "haqida", "nimadir", "kimdir", "gapirib", "gapir", "aytib", "ayt",
                "ber", "menga", "degani", "u", "bu", "o'zi", "qayerda", "joylashgan", "what", "is", "who", "was", "are",
                "tell", "me", "about", "a", "an", "the", "please", "iltimos", "ma'lumot");
        if (topic.isEmpty()) {
            say(t("Nima haqida bilmoqchisiz, Sir?", "What would you like to know, Sir?"));
            return;
        }
        exec.execute(() -> {
            String ans = wikiSummary(uz() ? "uz" : "en", topic);
            if (ans != null) {
                say(ans);
            } else {
                say(t("Vikipediyada topa olmadim, Sir. Google'da ochyapman.", "Nothing on Wikipedia, Sir. Opening Google."));
                search(topic);
            }
        });
    }

    private static String wikiSummary(String lang, String q) {
        try {
            String base = "https://" + lang + ".wikipedia.org";
            String found = httpGet(base + "/w/api.php?action=query&list=search&format=json&srlimit=1&srsearch="
                    + URLEncoder.encode(q, "UTF-8"));
            JSONArray arr = new JSONObject(found).getJSONObject("query").getJSONArray("search");
            if (arr.length() == 0) return null;
            String title = arr.getJSONObject(0).getString("title");
            String sum = httpGet(base + "/api/rest_v1/page/summary/"
                    + URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20"));
            String extract = new JSONObject(sum).optString("extract", "").trim();
            if (extract.isEmpty()) return null;
            return firstSentences(extract, 2, 400);
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstSentences(String text, int count, int maxLen) {
        String[] parts = text.split("(?<=[.!?])\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length && i < count; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(parts[i]);
        }
        String out = sb.toString();
        if (out.length() > maxLen) out = out.substring(0, maxLen).trim() + "...";
        return out;
    }

    // ---------------------------------------------------------------- multi-step dialog

    private static String lightNorm(String raw) {
        return raw.trim()
                .replaceAll("[ʻʼ’‘`´]", "'")
                .replaceFirst("(?i)^(hey |salom )?jarvis[,!.]?\\s*", "")
                .trim();
    }

    private boolean handlePending(String raw, String s) {
        List<String> w = Arrays.asList(s.split(" "));
        boolean cancel = hasWord(w, "bekor", "cancel") || has(s, "kerak emas", "bekor qil", "to'xta")
                || (pending != SMS_TEXT && hasWord(w, "yo'q", "no", "yoq", "nope"));
        if (cancel) {
            pending = NONE;
            say(t("Bekor qilindi, Sir.", "Cancelled, Sir."));
            return true;
        }
        switch (pending) {
            case SMS_TO: {
                pending = NONE;
                String who = stripSuffix(stripWords(s, "unga", "to", "for"), "ga", "ka", "qa");
                smsResolve(who, smsText);
                return true;
            }
            case SMS_TEXT: {
                String text = lightNorm(raw);
                if (text.isEmpty()) {
                    host.ask(t("Nima deb yozay, Sir?", "What should I write, Sir?"));
                    return true;
                }
                smsText = text;
                pending = SMS_CONFIRM;
                askConfirm();
                return true;
            }
            case SMS_CONFIRM: {
                if (hasWord(w, "ha", "xa", "yubor", "jo'nat", "albatta", "mayli", "ok", "okey", "yes", "send", "xop", "hop", "bo'pti", "yep")
                        || has(s, "ha yubor", "yuboraver")) {
                    smsSend();
                } else {
                    host.ask(t("Yuboraymi, Sir? Ha yoki yo'q deng.", "Shall I send it, Sir? Say yes or no."));
                }
                return true;
            }
            default:
                pending = NONE;
                return false;
        }
    }

    // ---------------------------------------------------------------- SMS

    private void smsStart(String raw) {
        String light = lightNorm(raw);
        String name = null;
        String text = null;
        String nameRe = "((?:\\S+ )*?\\S+?)(?:ga|ka|qa)";
        int ci = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        Matcher m;
        if ((m = Pattern.compile("^" + nameRe + " (.+?) (?:deb|degan) (?:sms|xabar)\\S*.*$", ci).matcher(light)).matches()) {
            name = m.group(1);
            text = m.group(2);
        } else if ((m = Pattern.compile("^" + nameRe + " (?:sms|xabar)\\S*(?: (?:yoz|yubor|jo'nat)\\S*){0,2}(?: (?:ber|bering|qil|qiling))?(?: (.+))?$", ci).matcher(light)).matches()) {
            name = m.group(1);
            text = m.group(2);
        } else if ((m = Pattern.compile("^(?:sms|xabar)\\S*(?: (?:yoz|yubor|jo'nat)\\S*){0,2}(?: (?:ber|bering))? " + nameRe + "(?: (.+))?$", ci).matcher(light)).matches()) {
            name = m.group(1);
            text = m.group(2);
        } else if ((m = Pattern.compile("^(?:please )?(?:send |write )?(?:an? )?(?:sms|text|message)(?: message)? to (\\S+)(?: (.+))?$", ci).matcher(light)).matches()) {
            name = m.group(1);
            text = m.group(2);
        }
        if (text != null) {
            text = text.replaceFirst("(?i)^(?:deb|that|saying)\\s+", "").trim();
            if (text.isEmpty()) text = null;
        }
        smsText = text;
        if (name == null || name.trim().isEmpty()) {
            pending = SMS_TO;
            host.ask(t("Kimga yozay, Sir?", "Who should I text, Sir?"));
            return;
        }
        smsResolve(name.trim().toLowerCase(Locale.ROOT), text);
    }

    private void smsResolve(String who, String text) {
        if (who.isEmpty()) {
            pending = SMS_TO;
            host.ask(t("Kimga yozay, Sir?", "Who should I text, Sir?"));
            return;
        }
        String digits = who.replaceAll("[^0-9+]", "");
        if (digits.replace("+", "").length() >= 5) {
            smsNumber = digits;
            smsTo = digits;
        } else {
            if (act.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                pending = NONE;
                say(t("Kontaktlarni o'qishga ruxsat bering, Sir, keyin davom etaman.", "Please allow contacts access, Sir."));
                String retry = who + "ga sms yoz" + (text != null ? " " + text : "");
                host.requestPerms(new String[]{Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS}, retry);
                return;
            }
            String[] found = findContact(who);
            if (found == null) {
                pending = NONE;
                say(t("Kontaktlarda \"" + who + "\" topilmadi, Sir.", "I couldn't find \"" + who + "\" in contacts, Sir."));
                return;
            }
            smsTo = found[0];
            smsNumber = found[1];
        }
        smsText = text;
        if (text == null) {
            pending = SMS_TEXT;
            host.ask(t(smsTo + "ga nima deb yozay, Sir?", "What should I say to " + smsTo + ", Sir?"));
        } else {
            pending = SMS_CONFIRM;
            askConfirm();
        }
    }

    private void askConfirm() {
        host.ask(t(smsTo + "ga: \"" + smsText + "\". Yuboraymi, Sir?",
                "To " + smsTo + ": \"" + smsText + "\". Shall I send it, Sir?"));
    }

    @SuppressWarnings("deprecation")
    private void smsSend() {
        if (act.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            say(t("SMS yuborishga ruxsat bering, Sir.", "Please allow sending SMS, Sir."));
            host.requestPerms(new String[]{Manifest.permission.SEND_SMS}, "ha");
            return;
        }
        pending = NONE;
        try {
            SmsManager sm = Build.VERSION.SDK_INT >= 31 ? act.getSystemService(SmsManager.class) : SmsManager.getDefault();
            ArrayList<String> parts = sm.divideMessage(smsText);
            if (parts.size() > 1) sm.sendMultipartTextMessage(smsNumber, null, parts, null, null);
            else sm.sendTextMessage(smsNumber, null, smsText, null, null);
            say(t("Xabar " + smsTo + "ga yuborildi, Sir.", "Message sent to " + smsTo + ", Sir."));
        } catch (Exception e) {
            say(t("SMS yuborib bo'lmadi, Sir. SIM karta va balansni tekshiring.", "Couldn't send the SMS, Sir. Check your SIM card."));
        }
    }

    // ---------------------------------------------------------------- memory

    private List<String> loadNotes() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString("notes", "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    private void saveNotes(List<String> notes) {
        JSONArray arr = new JSONArray();
        for (String n : notes) arr.put(n);
        prefs.edit().putString("notes", arr.toString()).apply();
    }

    private void rememberNote(String raw) {
        String note = lightNorm(raw)
                .replaceAll("(?i)\\b(eslab qol(gin|ing)?|yodda tut(gin|ing)?|esingda tut(gin|ing)?|remember( that)?)\\b[:,]?", " ")
                .replaceAll("\\s+", " ")
                .replaceAll("^[\\s,.:]+|[\\s,.:]+$", "")
                .trim();
        if (note.isEmpty()) {
            say(t("Nimani eslab qolay, Sir?", "What should I remember, Sir?"));
            return;
        }
        List<String> notes = loadNotes();
        notes.add(note);
        while (notes.size() > 30) notes.remove(0);
        saveNotes(notes);
        say(t("Eslab qoldim, Sir: " + note, "Got it, Sir. I'll remember: " + note));
    }

    private void recallNotes() {
        List<String> notes = loadNotes();
        String name = prefs.getString("user_name", "");
        if (notes.isEmpty() && name.isEmpty()) {
            say(t("Hozircha hech narsa eslab qolmadim, Sir. \"Eslab qol, ...\" deb ayting.", "Nothing saved yet, Sir."));
            return;
        }
        StringBuilder sb = new StringBuilder(t("Eslab qolganlarim, Sir: ", "Here's what I remember, Sir: "));
        if (!name.isEmpty()) sb.append(t("ismingiz " + name + ". ", "your name is " + name + ". "));
        for (int i = 0; i < notes.size(); i++) sb.append(i + 1).append(") ").append(notes.get(i)).append(". ");
        say(sb.toString().trim());
    }

    private void forgetNotes() {
        prefs.edit().remove("notes").remove("user_name").apply();
        history.clear();
        say(t("Xotiram tozalandi, Sir.", "Memory cleared, Sir."));
    }

    private void setName(String raw) {
        Matcher m = Pattern.compile("(?i)(?:mening ismim|ismim|my name is)\\s+(\\S+)").matcher(lightNorm(raw));
        if (!m.find()) {
            say(t("Ismingizni tushunmadim, Sir.", "I didn't catch your name, Sir."));
            return;
        }
        String name = capitalize(m.group(1).replaceAll("[^\\p{L}'-]", ""));
        prefs.edit().putString("user_name", name).apply();
        say(t("Tanishganimdan xursandman, " + name + ". Lekin men sizni baribir Sir deb chaqiraman.",
                "Nice to meet you, " + name + ". I'll still call you Sir."));
    }

    private void tellName() {
        String name = prefs.getString("user_name", "");
        say(name.isEmpty()
                ? t("Hali ismingizni aytmagansiz, Sir. \"Mening ismim ...\" deb ayting.", "You haven't told me your name yet, Sir.")
                : t("Sizning ismingiz " + name + ", Sir.", "Your name is " + name + ", Sir."));
    }

    // ---------------------------------------------------------------- AI (kalitsiz)

    private String systemPrompt() {
        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm, EEEE", Locale.US).format(Calendar.getInstance().getTime());
        String name = prefs.getString("user_name", "");
        List<String> notes = loadNotes();
        StringBuilder sb = new StringBuilder();
        if (uz()) {
            sb.append("Sen J.A.R.V.I.S. — foydalanuvchining Android telefonidagi aqlli shaxsiy ovozli yordamchisisan. ")
              .append("Foydalanuvchini doim \"Sir\" deb chaqir. Xushmuomala, aqlli, biroz hazilkash bo'l, xuddi Temir odamning Jarvisi kabi. ")
              .append("Javoblaring qisqa bo'lsin (1-3 gap), chunki ular ovoz bilan o'qiladi. Markdown, ro'yxat, emoji ishlatma. ")
              .append("Faqat o'zbek tilida, lotin yozuvida javob ber. Bilmasang, to'qib chiqarma, bilmasligingni ayt. ")
              .append("Hozirgi vaqt: ").append(now).append(". ")
              .append("MUHIM: agar foydalanuvchi telefonda amal bajarishni so'rasa, boshqa hech narsa yozmasdan faqat bitta qator qaytar: ")
              .append("CMD: <buyruq>. Mavjud buyruqlar: <ilova>ni och; fonarni yoq; fonarni o'chir; soat nechi; bugun nechanchi sana; ")
              .append("soat HH:MM da budilnik qo'y; N daqiqaga taymer qo'y; <ism>ga qo'ng'iroq qil; <ism>ga sms yoz <matn>; ")
              .append("<shahar>da ob-havo; google'da qidir <so'z>; youtube'da <so'z> qo'y; <joy>ga yo'l ko'rsat; ")
              .append("ovozni balandlat; ovozni pasaytir; ovozni o'chir; wifi; bluetooth; batareya; eslab qol <matn>. ")
              .append("Masalan: \"menga biror musiqa qo'yib ber\" -> CMD: youtube'da musiqa qo'y. ")
              .append("Agar so'rov bu ro'yxatda bo'lmagan amal bo'lsa, uni qila olmasligingni qisqa ayt. Oddiy savol yoki suhbatga oddiy javob ber.");
            if (!name.isEmpty()) sb.append(" Foydalanuvchining ismi: ").append(name).append(".");
            if (!notes.isEmpty()) sb.append(" Foydalanuvchi eslab qolishni so'ragan narsalar: ").append(String.join("; ", notes)).append(".");
        } else {
            sb.append("You are J.A.R.V.I.S., the user's smart personal voice assistant on their Android phone. ")
              .append("Always address the user as \"Sir\". Be polite, clever and a little witty, like Iron Man's Jarvis. ")
              .append("Keep answers short (1-3 sentences) because they are spoken aloud. No markdown, lists or emoji. ")
              .append("If you don't know, say so; never make things up. Current time: ").append(now).append(". ")
              .append("IMPORTANT: if the user asks for a phone action, reply with exactly one line and nothing else: CMD: <command>. ")
              .append("Available commands: open <app>; flashlight on; flashlight off; what time is it; what's the date; ")
              .append("set an alarm for HH:MM; timer for N minutes; call <name>; send sms to <name> <text>; weather in <city>; ")
              .append("search google for <words>; play <words> on youtube; navigate to <place>; volume up; volume down; mute; ")
              .append("wifi; bluetooth; battery; remember <text>. Otherwise just answer normally.");
            if (!name.isEmpty()) sb.append(" The user's name: ").append(name).append(".");
            if (!notes.isEmpty()) sb.append(" Things the user asked you to remember: ").append(String.join("; ", notes)).append(".");
        }
        return sb.toString();
    }

    private void askAi(String raw) {
        final String userText = lightNorm(raw);
        host.status(t("O'ylayapman...", "Thinking..."));
        exec.execute(() -> {
            String reply = aiChat(userText);
            if (reply == null) {
                String wiki = wikiSummary(uz() ? "uz" : "en", stripWords(norm(userText), "nima", "kim", "haqida", "menga",
                        "aytib", "ber", "gapirib", "what", "is", "who", "tell", "me", "about", "the"));
                if (wiki != null) {
                    say(wiki);
                } else {
                    say(t("Aql serveriga ulana olmadim, Sir. Google'da qidiryapman.", "I couldn't reach my AI server, Sir. Searching Google."));
                    search(userText);
                }
                return;
            }
            int idx = reply.toUpperCase(Locale.ROOT).indexOf("CMD:");
            if (idx >= 0) {
                String cmd = reply.substring(idx + 4).split("\n")[0].replace("\"", "").trim();
                if (!cmd.isEmpty()) {
                    main.post(() -> handleInternal(cmd, false));
                    return;
                }
            }
            String clean = reply.replaceAll("[*#`_]", "").replaceFirst("(?i)^jarvis:\\s*", "").replaceAll("\\n{2,}", "\n").trim();
            addHistory("user", userText);
            addHistory("assistant", clean);
            host.ask(clean);
        });
    }

    private void addHistory(String role, String text) {
        history.add(new String[]{role, text.length() > 600 ? text.substring(0, 600) : text});
        while (history.size() > 8) history.remove(0);
    }

    /** Avval LLM7, keyin Pollinations. Ikkalasi ham API kalit talab qilmaydi. */
    private String aiChat(String userText) {
        String system = systemPrompt();
        try {
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "system").put("content", system));
            for (String[] h : history) msgs.put(new JSONObject().put("role", h[0]).put("content", h[1]));
            msgs.put(new JSONObject().put("role", "user").put("content", userText));
            for (String model : new String[]{"gpt-4.1-nano-2025-04-14", "gpt-4o-mini"}) {
                try {
                    JSONObject body = new JSONObject().put("model", model).put("messages", msgs)
                            .put("temperature", 0.6).put("max_tokens", 400);
                    String resp = httpPostJson("https://api.llm7.io/v1/chat/completions", body.toString(), "Bearer unused");
                    String content = new JSONObject(resp).getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").optString("content", "").trim();
                    if (!content.isEmpty()) return content;
                } catch (HttpException e) {
                    if (e.code == 429 || e.code >= 500) break;
                }
            }
        } catch (Exception ignored) {
        }
        try {
            StringBuilder p = new StringBuilder();
            int from = Math.max(0, history.size() - 4);
            for (int i = from; i < history.size(); i++) {
                String[] h = history.get(i);
                String txt = h[1].length() > 300 ? h[1].substring(0, 300) : h[1];
                p.append(h[0].equals("user") ? "User: " : "Jarvis: ").append(txt).append('\n');
            }
            p.append("User: ").append(userText).append("\nJarvis:");
            String url = "https://text.pollinations.ai/" + enc(p.toString()) + "?system=" + enc(system);
            String content = httpGet(url).trim();
            if (!content.isEmpty() && !content.startsWith("{") && !content.startsWith("<")) return content;
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    }

    static final class HttpException extends Exception {
        final int code;

        HttpException(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    private static String httpPostJson(String url, String body, String auth) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setConnectTimeout(10000);
        con.setReadTimeout(30000);
        con.setRequestProperty("Content-Type", "application/json");
        con.setRequestProperty("Authorization", auth);
        con.setRequestProperty("User-Agent", "JarvisAndroid/2.0");
        try {
            try (OutputStream os = con.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = con.getResponseCode();
            if (code >= 400) throw new HttpException(code);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            return sb.toString();
        } finally {
            con.disconnect();
        }
    }

    // ---------------------------------------------------------------- network

    private static String httpGet(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(10000);
        con.setRequestProperty("User-Agent", "curl/8.0 (JarvisAndroid/1.0 personal assistant)");
        try {
            int code = con.getResponseCode();
            if (code >= 400) throw new IllegalStateException("HTTP " + code);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            return sb.toString();
        } finally {
            con.disconnect();
        }
    }
}
