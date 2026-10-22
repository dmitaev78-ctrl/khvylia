package dev.khvylia.radio;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.AudioFocusRequest;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;

public final class MainActivity extends Activity {
    private final int ink = Color.rgb(16, 27, 41), mint = Color.rgb(102, 237, 181), muted = Color.rgb(161, 179, 195);
    private EditText host, port, channel, token;
    private TextView status, members, identity;
    private Button connect, talk;
    private RadioClient client;
    private boolean joined, held, suppressClick;
    private String speaker = "";
    private int connectionSerial;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocus;
    private SharedPreferences preferences;
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        audioFocus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(change -> { if (change < 0 && client != null) disconnect("Эфир прерван другим аудиоприложением или звонком."); }).build();
        preferences = getSharedPreferences("connection", MODE_PRIVATE);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(20), dp(24), dp(24));
        page.setBackgroundColor(ink);
        scroll.addView(page);
        setContentView(scroll);
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(24) + insets.getSystemWindowInsetLeft(), dp(20) + insets.getSystemWindowInsetTop(), dp(24) + insets.getSystemWindowInsetRight(), dp(24) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        page.requestApplyInsets();
        TextView logo = label("ХВИЛЯ  ≋", 34, mint);
        logo.setTypeface(Typeface.DEFAULT, Typeface.BOLD); page.addView(logo);
        page.addView(label("Твой голос. Твой канал.", 16, muted));
        identity = label("РАЦИЯ  /  0.1", 12, muted); add(page, identity, 24);
        status = label("Не подключено", 22, Color.WHITE); add(page, status, 8);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        members = label("Подключитесь к серверу, чтобы войти в эфир.", 15, muted); add(page, members, 8);
        talk = new Button(this);
        talk.setText("УДЕРЖИВАЙ\nИ ГОВОРИ"); talk.setTextSize(23); talk.setTextColor(ink);
        talk.setAllCaps(false); talk.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        talk.setBackground(shape(mint, 44)); talk.setEnabled(false);
        LinearLayout.LayoutParams talkParams = new LinearLayout.LayoutParams(-1, dp(180));
        talkParams.topMargin = dp(24); talkParams.bottomMargin = dp(12); page.addView(talk, talkParams);
        talk.setContentDescription("Передача голоса. Удерживайте кнопку. С экранным диктором: активируйте для начала и повторно для остановки.");
        talk.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                view.getParent().requestDisallowInterceptTouchEvent(true); beginTalk(); return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                endTalk(); view.getParent().requestDisallowInterceptTouchEvent(false);
                if (event.getActionMasked() == MotionEvent.ACTION_UP) { suppressClick = true; view.performClick(); suppressClick = false; }
                return true;
            }
            return true;
        });
        talk.setOnClickListener(view -> { if (!suppressClick) { if (held) endTalk(); else beginTalk(); } });
        page.addView(label("Один говорящий • до 30 секунд за передачу", 13, muted));
        host = field(page, "Сервер", preferences.getString("host", ""), "radio.example.org", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        port = field(page, "Порт", preferences.getString("port", "8443"), "8443", InputType.TYPE_CLASS_NUMBER);
        channel = field(page, "Канал", preferences.getString("channel", "general"), "general", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        token = field(page, "Личный ключ доступа", "", "Выдаёт администратор сервера", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setSaveEnabled(false); token.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        connect = new Button(this); connect.setText("Подключиться"); connect.setAllCaps(false);
        add(page, connect, 16); connect.setOnClickListener(view -> { if (client == null) connect(); else disconnect("Вы отключились."); });
        TextView hint = label("Нужен сервер Хвиля с TLS-сертификатом и ваш ключ. Приложение работает при открытом экране; сворачивание отключает эфир. Голос не записывается в файлы.", 13, muted);
        add(page, hint, 16);
    }
    private GradientDrawable shape(int color, int radius) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable; }
    private TextView label(String text, int size, int color) { TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(color); return v; }
    private void add(LinearLayout parent, View view, int margin) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(margin); parent.addView(view, p); }
    private EditText field(LinearLayout page, String title, String value, String hint, int input) {
        TextView caption = label(title, 13, muted); add(page, caption, 18);
        EditText edit = new EditText(this); edit.setId(View.generateViewId()); caption.setLabelFor(edit.getId());
        edit.setText(value); edit.setHint(hint); edit.setTextColor(Color.WHITE); edit.setHintTextColor(muted);
        edit.setTextSize(16); edit.setSingleLine(true); edit.setInputType(input); edit.setPadding(dp(12), dp(12), dp(12), dp(12));
        edit.setBackground(shape(Color.rgb(29, 44, 60), 12)); add(page, edit, 6); return edit;
    }
    private void fields(boolean enabled) { for (EditText field : new EditText[]{host, port, channel, token}) field.setEnabled(enabled); }
    private void connect() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7); return; }
        String server = host.getText().toString().trim(), room = channel.getText().toString().trim(), secret = token.getText().toString().trim();
        int number;
        try { number = Integer.parseInt(port.getText().toString().trim()); } catch (NumberFormatException e) { status.setText("Введите порт от 1 до 65535"); return; }
        if (!server.matches("[a-zA-Z0-9.-]{1,253}") || number < 1 || number > 65535 || !room.matches("[a-zA-Z0-9_-]{1,32}") || secret.length() < 32 || secret.length() > 256) {
            status.setText("Проверьте сервер, порт, канал и ключ"); return;
        }
        if (audioManager.requestAudioFocus(audioFocus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { status.setText("Звук занят другим приложением. Попробуйте позже."); return; }
        preferences.edit().putString("host", server).putString("port", String.valueOf(number)).putString("channel", room).apply();
        fields(false); status.setText("Подключение…"); connect.setText("Отменить"); token.setText("");
        final int serial = ++connectionSerial;
        RadioClient created = new RadioClient(new RadioClient.Listener() {
            @Override public void event(JSONObject message) { runOnUiThread(() -> { if (serial == connectionSerial && client != null) handle(message); }); }
            @Override public void closed(String reason) { runOnUiThread(() -> { if (serial == connectionSerial && client != null) reset(reason); }); }
        });
        client = created;
        created.connect(server, number, secret, room);
    }
    private void handle(JSONObject msg) {
        switch (msg.optString("type")) {
            case "joined":
                joined = true; talk.setEnabled(true); connect.setText("Отключиться");
                identity.setText(msg.optString("name") + "  /  #" + msg.optString("channel"));
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); renderIdle(); break;
            case "state":
                speaker = msg.optString("speaker");
                JSONArray names = msg.optJSONArray("members"); StringBuilder list = new StringBuilder("В канале: ");
                if (names != null) for (int i=0; i<names.length(); i++) { if (i>0) list.append(", "); list.append(names.optString(i)); }
                members.setText(list.toString()); if (!held) renderIdle(); break;
            case "granted":
                if (held && client != null) { status.setText("Вы в эфире"); talk.setText("ГОВОРИТЕ\n● В ЭФИРЕ"); talk.setBackground(shape(Color.rgb(255, 180, 115), 44)); client.startMicrophone(); }
                else if (client != null) client.stopMicrophone(true); break;
            case "busy":
                held = false; renderIdle(); status.setText("Канал занят. Попробуйте после говорящего."); break;
            case "released":
                held = false; if (client != null) client.stopMicrophone(false); renderIdle(); break;
            default: break;
        }
    }
    private void beginTalk() {
        if (!joined || client == null || held) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7); return;
        }
        held = true; status.setText("Запрос эфира…"); client.send(RadioClient.message("claim"));
    }
    private void endTalk() { if (!held) return; held = false; if (client != null) client.stopMicrophone(true); renderIdle(); }
    private void renderIdle() { talk.setText("УДЕРЖИВАЙ\nИ ГОВОРИ"); talk.setBackground(shape(mint, 44)); status.setText(speaker.isEmpty() ? "Канал свободен" : "Говорит: " + speaker); }
    private void reset(String reason) {
        client = null; joined = false; held = false; speaker = ""; talk.setEnabled(false); renderIdle();
        status.setText(reason); identity.setText("РАЦИЯ  /  0.1"); members.setText("Нет подключения"); connect.setText("Подключиться"); fields(true);
        audioManager.abandonAudioFocusRequest(audioFocus);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void disconnect(String reason) { connectionSerial++; RadioClient old = client; if (old != null) { client = null; old.close(reason); } reset(reason); }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 7) status.setText(results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED ? "Микрофон разрешён. Подключитесь и удерживайте кнопку." : "Для передачи разрешите микрофон в настройках приложения.");
    }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (!focus && client != null) endTalk(); }
    @Override protected void onPause() { if (client != null) disconnect("Эфир отключён при выходе с экрана."); super.onPause(); }
}
