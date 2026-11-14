package uz.jarvis.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements CommandEngine.Host {

    static final String EXTRA_LISTEN = "listen";
    private static final String CHANNEL_ID = "jarvis_quick";
    private static final int NOTIF_ID = 7;
    private static final int REQ_BASIC = 1;
    private static final int REQ_EXTRA = 2;

    private static final int CYAN = Color.parseColor("#00E5FF");
    private static final int RED = Color.parseColor("#FF4D6D");
    private static final int BG = Color.parseColor("#0A0F1C");

    private SharedPreferences prefs;
    private CommandEngine engine;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String pendingSpeech;
    private boolean listening = false;
    private String pendingCommand;

    private TextView logView;
    private TextView statusView;
    private ScrollView scroll;
    private Button micButton;
    private Button langButton;
    private Button pinButton;
    private EditText input;
    private GradientDrawable micCircle;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("jarvis", MODE_PRIVATE);
        engine = new CommandEngine(this, this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();
        initTts();
        requestBasicPermissions();
        if (prefs.getBoolean("pin", false)) showQuickNotification();

        if (wantsListen(getIntent())) {
            micButton.postDelayed(this::startListening, 600);
        } else {
            say(t("Salom, Sir. Jarvis xizmatingizga tayyor.", "Hello, Sir. Jarvis at your service."));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (wantsListen(intent)) micButton.postDelayed(this::startListening, 300);
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) recognizer.destroy();
        if (tts != null) tts.shutdown();
        engine.shutdown();
        super.onDestroy();
    }

    private boolean wantsListen(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        return intent.getBooleanExtra(EXTRA_LISTEN, false)
                || Intent.ACTION_ASSIST.equals(action)
                || Intent.ACTION_VOICE_COMMAND.equals(action);
    }

    // ---------------------------------------------------------------- UI

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("J.A.R.V.I.S.");
        title.setTextColor(CYAN);
        title.setTextSize(24);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        langButton = smallButton("UZ");
        langButton.setOnClickListener(v -> toggleLang());
        top.addView(langButton);
        pinButton = smallButton("📌");
        pinButton.setOnClickListener(v -> toggleQuickNotification());
        top.addView(pinButton);
        root.addView(top);

        statusView = new TextView(this);
        statusView.setTextColor(Color.parseColor("#7FA7B8"));
        statusView.setTextSize(13);
        statusView.setPadding(0, dp(4), 0, 0);
        root.addView(statusView);

        scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextColor(Color.parseColor("#DDE6EE"));
        logView.setTextSize(16);
        logView.setLineSpacing(0f, 1.2f);
        logView.setPadding(0, dp(12), 0, dp(12));
        logView.setTextIsSelectable(true);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        micButton = new Button(this);
        micCircle = new GradientDrawable();
        micCircle.setShape(GradientDrawable.OVAL);
        micCircle.setColor(Color.parseColor("#0E2A35"));
        micCircle.setStroke(dp(3), CYAN);
        micButton.setBackground(micCircle);
        micButton.setText("🎙");
        micButton.setTextSize(36);
        micButton.setOnClickListener(v -> {
            if (listening) stopListening();
            else startListening();
        });
        LinearLayout.LayoutParams micLp = new LinearLayout.LayoutParams(dp(120), dp(120));
        micLp.gravity = Gravity.CENTER_HORIZONTAL;
        micLp.topMargin = dp(8);
        micLp.bottomMargin = dp(12);
        root.addView(micButton, micLp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(this);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.parseColor("#557080"));
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, actionId, event) -> {
            sendTyped();
            return true;
        });
        row.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button send = smallButton("➤");
        send.setOnClickListener(v -> sendTyped());
        row.addView(send);
        root.addView(row);

        setContentView(root);
        refreshLabels();
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(CYAN);
        b.setAllCaps(false);
        b.setMinWidth(dp(48));
        b.setMinimumWidth(dp(48));
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(10));
        d.setColor(Color.parseColor("#12202C"));
        b.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
        lp.leftMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private void refreshLabels() {
        langButton.setText(isUz() ? "UZ" : "EN");
        pinButton.setAlpha(prefs.getBoolean("pin", false) ? 1f : 0.5f);
        input.setHint(t("Yoki shu yerga yozing...", "Or type here..."));
        if (!listening) {
            statusView.setText(t("Tayyor, Sir. Mikrofonni bosing.", "Ready, Sir. Tap the mic."));
        }
    }

    private void appendLog(String who, String text) {
        logView.append(who + "  " + text + "\n\n");
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void sendTyped() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        input.setText("");
        handleCommand(text);
    }

    private void handleCommand(String text) {
        appendLog("🧑", text);
        engine.handle(text);
    }

    // ---------------------------------------------------------------- language

    private boolean isUz() {
        return "uz".equals(prefs.getString("lang", "uz"));
    }

    private String t(String uz, String en) {
        return isUz() ? uz : en;
    }

    private void toggleLang() {
        prefs.edit().putString("lang", isUz() ? "en" : "uz").apply();
        applyTtsLanguage();
        refreshLabels();
        say(t("Endi o'zbek tilida gaplashamiz, Sir.", "Switched to English, Sir."));
    }

    // ---------------------------------------------------------------- Host

    @Override
    public String lang() {
        return isUz() ? "uz" : "en";
    }

    @Override
    public void say(String text) {
        runOnUiThread(() -> {
            appendLog("🤖", text);
            if (ttsReady) speak(text);
            else pendingSpeech = text;
        });
    }

    @Override
    public void stopSpeaking() {
        runOnUiThread(() -> {
            if (tts != null) tts.stop();
        });
    }

    @Override
    public void requestPerms(String[] perms, String retryCommand) {
        runOnUiThread(() -> {
            pendingCommand = retryCommand;
            requestPermissions(perms, REQ_EXTRA);
        });
    }

    // ---------------------------------------------------------------- TTS

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
                applyTtsLanguage();
                if (pendingSpeech != null) {
                    speak(pendingSpeech);
                    pendingSpeech = null;
                }
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void applyTtsLanguage() {
        if (!ttsReady) return;
        Locale[] tries = isUz()
                ? new Locale[]{new Locale("uz", "UZ"), new Locale("uz"), new Locale("tr", "TR"), Locale.US}
                : new Locale[]{Locale.US, Locale.UK};
        for (Locale l : tries) {
            int r = tts.setLanguage(l);
            if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) return;
        }
    }

    private void speak(String text) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis" + System.currentTimeMillis());
    }

    // ---------------------------------------------------------------- speech recognition

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) {
            statusView.setText(t("Eshityapman, Sir...", "Listening, Sir..."));
        }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) {
            float scale = 1f + Math.max(0f, Math.min(rmsdB, 10f)) / 40f;
            micButton.setScaleX(scale);
            micButton.setScaleY(scale);
        }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() {
            statusView.setText(t("O'ylayapman...", "Thinking..."));
        }
        @Override public void onError(int error) {
            setListening(false);
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    statusView.setText(t("Hech narsa eshitmadim, Sir. Qaytadan bosing.", "I didn't catch that, Sir."));
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    say(t("Ovozni tanish uchun internet kerak, Sir.", "Speech recognition needs internet, Sir."));
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    say(t("Mikrofonga ruxsat bering, Sir.", "Please allow microphone access, Sir."));
                    break;
                case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
                case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                    say(t("Telefoningizdagi ovozni tanish xizmati o'zbek tilini qo'llamayapti. Google ilovasi sozlamalaridan o'zbek tilini qo'shing yoki EN tugmasini bosing, Sir.",
                            "This language isn't supported by your speech service, Sir."));
                    break;
                default:
                    statusView.setText(t("Xatolik (" + error + "). Qaytadan urinib ko'ring, Sir.", "Error (" + error + "). Please try again, Sir."));
            }
        }
        @Override public void onResults(Bundle results) {
            setListening(false);
            ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list != null && !list.isEmpty()) handleCommand(list.get(0));
        }
        @Override public void onPartialResults(Bundle partialResults) {
            ArrayList<String> list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list != null && !list.isEmpty() && !list.get(0).isEmpty()) statusView.setText("… " + list.get(0));
        }
        @Override public void onEvent(int eventType, Bundle params) { }
    };

    private void startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_BASIC);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say(t("Telefoningizda ovozni tanish xizmati topilmadi. Google ilovasini o'rnating yoki yangilang, Sir.",
                    "No speech recognition service found. Please install or update the Google app, Sir."));
            return;
        }
        if (tts != null) tts.stop();
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(listener);
        }
        String tag = isUz() ? "uz-UZ" : "en-US";
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        setListening(true);
        recognizer.startListening(i);
    }

    private void stopListening() {
        if (recognizer != null) recognizer.stopListening();
        setListening(false);
    }

    private void setListening(boolean on) {
        listening = on;
        micCircle.setStroke(dp(3), on ? RED : CYAN);
        if (!on) {
            micButton.setScaleX(1f);
            micButton.setScaleY(1f);
            refreshLabels();
        }
    }

    // ---------------------------------------------------------------- permissions

    private void requestBasicPermissions() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_BASIC);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_EXTRA) return;
        boolean all = grantResults.length > 0;
        for (int g : grantResults) if (g != PackageManager.PERMISSION_GRANTED) all = false;
        String retry = pendingCommand;
        pendingCommand = null;
        if (all && retry != null) {
            engine.handle(retry);
        } else if (!all) {
            say(t("Ruxsat berilmadi, Sir. Bu buyruqni bajara olmayman.", "Permission denied, Sir."));
        }
    }

    // ---------------------------------------------------------------- quick notification

    private void toggleQuickNotification() {
        boolean on = !prefs.getBoolean("pin", false);
        prefs.edit().putBoolean("pin", on).apply();
        if (on) {
            showQuickNotification();
            say(t("Bildirishnomalar panelida doimiy Jarvis tugmasi paydo bo'ldi, Sir.",
                    "A Jarvis button is now pinned in your notifications, Sir."));
        } else {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.cancel(NOTIF_ID);
            say(t("Doimiy tugma olib tashlandi, Sir.", "Pinned button removed, Sir."));
        }
        refreshLabels();
    }

    private void showQuickNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Jarvis", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
        Intent i = new Intent(this, MainActivity.class)
                .putExtra(EXTRA_LISTEN, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 1, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Jarvis")
                .setContentText(t("Gapirish uchun bosing, Sir", "Tap to talk, Sir"))
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
        nm.notify(NOTIF_ID, n);
    }
}
