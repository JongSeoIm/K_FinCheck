package com.kfincheck.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;

/** Single-process, ordered disk spool. A successful append means the file was forced before rename. */
public final class FileQuoteOutbox implements AutoCloseable {
    public record Pending(String topic, QuoteMessage message) { }
    private final Path directory;
    private final long maxBytes;
    private final int maxRecords;
    private final ObjectMapper mapper;
    private final NavigableMap<String, Long> files = new TreeMap<>();
    private final FileChannel lockChannel;
    private final FileLock lock;
    private long bytes;
    private long sequence;
    private boolean closed;

    public FileQuoteOutbox(Path directory, long maxBytes, int maxRecords, ObjectMapper mapper) throws IOException {
        if (maxBytes < 1 || maxRecords < 1) throw new IllegalArgumentException("전송 대기 용량은 양수여야 합니다.");
        this.directory = directory.toAbsolutePath().normalize();
        this.maxBytes = maxBytes; this.maxRecords = maxRecords; this.mapper = mapper;
        Files.createDirectories(this.directory);
        if (Files.isSymbolicLink(this.directory)) throw new IOException("전송 대기 경로에 심볼릭 링크를 사용할 수 없습니다.");
        lockChannel = FileChannel.open(this.directory.resolve("spool.lock"), CREATE, WRITE);
        FileLock acquired;
        try { acquired = lockChannel.tryLock(); }
        catch (RuntimeException | IOException e) { lockChannel.close(); throw new IOException("전송 대기 저장소 잠금 실패", e); }
        if (acquired == null) { lockChannel.close(); throw new IOException("다른 수집기가 전송 대기 저장소를 사용 중입니다."); }
        lock = acquired;
        try (var paths = Files.list(this.directory)) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                // An interrupted write is not silently discarded. Operator inspection is required.
                if (name.endsWith(".writing")) throw new IOException("완료되지 않은 전송 대기 파일을 확인하세요.");
                if (!name.endsWith(".pending")) continue;
                if (!name.matches("[0-9]{20}-[0-9a-f-]{36}\\.pending") || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("전송 대기 파일 형식 오류");
                long size = Files.size(path);
                if (size > maxBytes || size > 1_048_576) throw new IOException("전송 대기 파일 크기 오류");
                read(path); // Refuse corrupt records before opening the provider connection.
                files.put(name, size); bytes = Math.addExact(bytes, size);
                sequence = Math.max(sequence, Long.parseLong(name.substring(0, 20)));
            }
        } catch (RuntimeException | IOException e) { close(); throw e; }
        // Existing backlog is retained even if an operator lowers the configured capacity.
    }

    public synchronized void append(String topic, QuoteMessage message) throws IOException {
        ensureOpen();
        if (topic == null || !topic.matches("[a-zA-Z0-9._-]{1,249}") || topic.equals(".") || topic.equals(".."))
            throw new IllegalArgumentException("Kafka 토픽 형식 오류");
        byte[] value = mapper.writeValueAsBytes(new Pending(topic, message));
        if (value.length > 1_048_576 || files.size() >= maxRecords || value.length > maxBytes - bytes)
            throw new IOException("전송 대기 저장소 용량 부족");
        sequence = Math.incrementExact(sequence);
        String name = String.format(Locale.ROOT, "%020d-%s", sequence, message.eventId());
        Path writing = directory.resolve(name + ".writing");
        Path ready = directory.resolve(name + ".pending");
        try (var file = FileChannel.open(writing, CREATE_NEW, WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(value);
            while (buffer.hasRemaining()) file.write(buffer);
            file.force(true);
        }
        // Atomic moves are required; do not weaken the guarantee on an unsupported filesystem.
        Files.move(writing, ready, StandardCopyOption.ATOMIC_MOVE);
        files.put(ready.getFileName().toString(), (long)value.length); bytes += value.length;
    }

    public synchronized Pending first() throws IOException {
        ensureOpen();
        return files.isEmpty() ? null : read(directory.resolve(files.firstKey()));
    }

    public synchronized void acknowledged(Pending expected) throws IOException {
        Pending current = first();
        if (current == null || !current.equals(expected)) throw new IOException("전송 대기 순서가 변경되었습니다.");
        String key = files.firstKey();
        Files.delete(directory.resolve(key));
        bytes -= files.remove(key);
    }

    public synchronized int size() { return files.size(); }
    public synchronized boolean hasCapacity() { return !closed && files.size() < maxRecords && bytes < maxBytes; }
    private Pending read(Path path) throws IOException {
        Pending value = mapper.readValue(Files.readAllBytes(path), Pending.class);
        if (value == null || value.message() == null || value.topic() == null
                || !value.topic().matches("[a-zA-Z0-9._-]{1,249}")) throw new IOException("전송 대기 내용 오류");
        return value;
    }
    private void ensureOpen() throws IOException { if (closed) throw new IOException("전송 대기 저장소가 닫혔습니다."); }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { lock.release(); } finally { lockChannel.close(); }
    }
}
