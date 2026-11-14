package uz.jarvis.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
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
        String lang();
        void stopSpeaking();
        void requestPerms(String[] perms, String retryCommand);
    }

    private final Activity act;
    private final Host host;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private String lastCommand = "";

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
        lastCommand = raw;
        String s = norm(raw);
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
        if (has(s, "rahmat", "thank", "tashakkur")) {
            say(t("Arzimaydi, Sir. Doim xizmatingizdaman.", "You're welcome, Sir."));
            return;
        }
        if (w.size() <= 4 && has(s, "salom", "assalom", "hello", "hey", "qalaysan", "how are you")) {
            say(t("Salom, Sir! Hammasi joyida, tizimlar to'liq ishlayapti. Nima qilamiz?",
                    "Hello, Sir! All systems are online. What shall we do?"));
            return;
        }
        if (has(s, "budilnik", "uyg'ot", "alarm")) { alarm(s); return; }
        if (has(s, "taymer", "timer")) { timer(s); return; }
        if (has(s, "soat nech", "soat qancha", "vaqt qancha", "hozir vaqt", "what time", "the time")) { time(); return; }
        if (has(s, "bugun nechan", "sana", "qaysi kun", "bugun qaysi", "what day", "the date", "today's date")) { date(); return; }
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
        if (has(s, "qo'ng'iroq", "qong'iroq", "telefon qil", "call ", "zvonit", "qo'ng'iroq qil")) { call(s); return; }
        if (has(s, "ob-havo", "ob havo", "obhavo", "havo qanday", "weather")) { weather(w); return; }
        if (has(s, "youtube", "yutub", "yutyub", "youtub")) { youtube(s); return; }
        if (has(s, "yo'l ko'rsat", "qanday boraman", "xaritada", "navigate", "directions to", "show on map")) { maps(s); return; }
        if (hasWord(w, "qidir", "qidirib", "izla", "izlab", "google", "googlda", "search")) { search(stripWords(s,
                "qidir", "qidirib", "izla", "izlab", "ber", "google", "googleda", "googlda", "internetdan", "search", "for", "on", "da", "menga")); return; }
        if (s.matches(".*\\d.*") && tryMath(s)) return;
        if (hasWord(w, "och", "ochib", "ochgin", "oching", "ochchi", "open", "launch") || has(s, "ishga tushir")) { openApp(s); return; }
        if (hasWord(w, "nima", "kim", "haqida", "nimadir", "kimdir", "qayerda")
                || s.startsWith("what is") || s.startsWith("who is") || s.startsWith("who was")
                || s.startsWith("what are") || s.startsWith("tell me about")) {
            knowledge(s);
            return;
        }
        // Tushunmagan bo'lsa: avval Vikipediya, so'ng Google
        say(t("Bu buyruqni aniq tushunmadim, Sir. Internetdan izlab ko'raman.", "I'm not sure what you mean, Sir. Let me look it up."));
        knowledge(s);
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
        say(t("Men quyidagilarni qila olaman, Sir: ilovalarni ochish (Telegramni och), fonarni yoqish, "
                        + "soat va sanani aytish, budilnik va taymer qo'yish, qo'ng'iroq qilish (Aliga qo'ng'iroq qil), "
                        + "ob-havo (Samarqandda ob-havo qanday), Vikipediyadan ma'lumot (Ibn Sino kim), "
                        + "Google va YouTube'da qidirish, hisob-kitob (25 ko'paytir 4), ovoz balandligi, Wi-Fi va Bluetooth sozlamalari.",
                "I can open apps, toggle the flashlight, tell time and date, set alarms and timers, call contacts, "
                        + "check the weather, look things up on Wikipedia, search Google and YouTube, do math, change volume, "
                        + "and open Wi-Fi or Bluetooth settings, Sir."));
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
        String q = stripWords(s, "youtube", "youtubeda", "youtubedan", "youtubeni", "yutub", "yutubda", "yutubdan", "yutyub",
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
