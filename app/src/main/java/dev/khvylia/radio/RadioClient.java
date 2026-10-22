package dev.khvylia.radio;

import android.annotation.SuppressLint;
import android.media.*;
import android.util.Base64;
import org.json.*;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** TLS transport and bounded PCM queues. No credentials are persisted. */
final class RadioClient {
    interface Listener { void event(JSONObject message); void closed(String reason); }
    private final Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final BlockingQueue<String> outgoing = new ArrayBlockingQueue<>(64);
    private final BlockingQueue<byte[]> incoming = new ArrayBlockingQueue<>(20);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService microphone = Executors.newSingleThreadExecutor();
    private volatile Socket socket;
    private volatile boolean ready;
    private int recordingGeneration = 0;
    private boolean recording;

    RadioClient(Listener listener) { this.listener = listener; }
    static JSONObject message(String type) {
        JSONObject obj = new JSONObject();
        try { obj.put("type", type); } catch (JSONException ignored) { }
        return obj;
    }
    void connect(String host, int port, String token, String channel) {
        new Thread(() -> {
            try {
                Socket raw = new Socket();
                socket = raw;
                if (closed.get()) { raw.close(); return; }
                raw.connect(new InetSocketAddress(host, port), 10000);
                raw.setSoTimeout(15000);
                raw.setTcpNoDelay(true);
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
                socket = tls;
                if (closed.get()) { tls.close(); return; }
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                tls.setSSLParameters(parameters);
                tls.startHandshake();
                tls.setSoTimeout(45000);
                JSONObject auth = message("auth");
                auth.put("token", token); auth.put("channel", channel);
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(tls.getOutputStream(), StandardCharsets.UTF_8));
                writer.write(auth.toString()); writer.newLine(); writer.flush();
                new Thread(() -> writeLoop(writer), "radio-send").start();
                new Thread(this::playLoop, "radio-speaker").start();
                timer.scheduleAtFixedRate(() -> send(message("ping")), 15, 15, TimeUnit.SECONDS);
                InputStream input = new BufferedInputStream(tls.getInputStream());
                while (!closed.get()) {
                    JSONObject msg = new JSONObject(readLine(input));
                    String type = msg.optString("type");
                    if (type.equals("joined")) ready = true;
                    if (type.equals("audio")) {
                        byte[] bytes = Base64.decode(msg.getString("data"), Base64.NO_WRAP);
                        if (bytes.length != 640) throw new IOException("Invalid audio frame");
                        if (!incoming.offer(bytes)) { incoming.poll(); incoming.offer(bytes); }
                    } else listener.event(msg);
                }
            } catch (Exception e) {
                close(ready ? "Связь прервана. Подключитесь снова." : "Не удалось подключиться. Проверьте сервер, сертификат, ключ и канал.");
            }
        }, "radio-connect").start();
    }
    private String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = input.read()) != -1) {
            if (c == '\n') return line.toString("UTF-8");
            if (line.size() >= 8192) throw new IOException("Frame too large");
            line.write(c);
        }
        throw new EOFException();
    }
    void send(JSONObject message) {
        if (!closed.get() && !outgoing.offer(message.toString())) close("Сеть не успевает передавать звук. Подключитесь снова.");
    }
    private void writeLoop(BufferedWriter writer) {
        try {
            while (!closed.get()) {
                String msg = outgoing.poll(1, TimeUnit.SECONDS);
                if (msg != null) { writer.write(msg); writer.newLine(); writer.flush(); }
            }
        } catch (Exception e) { close("Соединение потеряно."); }
    }
    @SuppressLint("MissingPermission") // Activity checks RECORD_AUDIO before requesting the floor.
    synchronized void startMicrophone() {
        if (closed.get() || recording) return;
        recording = true;
        int generation = ++recordingGeneration;
        microphone.execute(() -> capture(generation));
    }
    private synchronized boolean isRecording(int generation) { return recording && generation == recordingGeneration && !closed.get(); }
    @SuppressLint("MissingPermission")
    private void capture(int generation) {
        AudioRecord recorder = null;
        try {
            int minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum, 6400));
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Microphone unavailable");
            recorder.startRecording();
            byte[] frame = new byte[640];
            while (isRecording(generation)) {
                int offset = 0;
                while (offset < frame.length && isRecording(generation)) {
                    int n = recorder.read(frame, offset, frame.length - offset);
                    if (n <= 0) throw new IOException("Recording failed");
                    offset += n;
                }
                synchronized (this) {
                    if (offset == frame.length && isRecording(generation)) {
                        JSONObject msg = message("audio");
                        msg.put("data", Base64.encodeToString(frame, Base64.NO_WRAP));
                        send(msg);
                    }
                }
            }
        } catch (Exception e) { close("Микрофон недоступен. Проверьте разрешение и другие приложения."); }
        finally {
            if (recorder != null) { try { recorder.stop(); } catch (IllegalStateException ignored) { } recorder.release(); }
        }
    }
    synchronized void stopMicrophone(boolean release) {
        recording = false;
        recordingGeneration++;
        if (release) send(message("release"));
    }
    private void playLoop() {
        AudioTrack track = null;
        try {
            int minimum = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(Math.max(minimum, 3200)).setTransferMode(AudioTrack.MODE_STREAM).build();
            track.play();
            while (!closed.get()) {
                byte[] pcm = incoming.poll(1, TimeUnit.SECONDS);
                if (pcm != null) {
                    int offset = 0;
                    while (offset < pcm.length && !closed.get()) {
                        int n = track.write(pcm, offset, pcm.length-offset);
                        if (n <= 0) throw new IOException("Playback failed");
                        offset += n;
                    }
                }
            }
        } catch (Exception e) { close("Ошибка воспроизведения звука."); }
        finally { if (track != null) { track.pause(); track.flush(); track.release(); } }
    }
    void close(String reason) {
        if (!closed.compareAndSet(false, true)) return;
        stopMicrophone(false);
        timer.shutdownNow(); microphone.shutdown();
        Socket current = socket;
        if (current != null) try { current.close(); } catch (IOException ignored) { }
        outgoing.clear(); incoming.clear();
        listener.closed(reason);
    }
}
