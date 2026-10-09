package dev.fpvcore.mc.demo;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import dev.fpvcore.mc.FpvClient;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/** Pipes every recorded frame (raw RGBA from the main render target) to an ffmpeg child process; writes a per-frame telemetry CSV for the audio render. */
public final class DemoRecorder {
    private DemoRecorder() {}

    private static Process ff;
    private static WritableByteChannel out;
    private static ByteBuffer buf;
    private static PrintWriter csv;
    public static long frames;
    private static int holds;
    private static int w, h;
    public static boolean enabled;     // the director turns recording on once the scene is ready
    public static String baseName = "take";
    public static java.util.function.Supplier<String> csvRow;

    /** Where the files go: -Dfpvcore.demo.out=<dir>. */
    public static Path outDir() { return Path.of(System.getProperty("fpvcore.demo.out", "demo-out")); }

    public static void start(Minecraft mc, String name) {
        RenderTarget rt = mc.getMainRenderTarget();
        w = rt.width; h = rt.height;
        try {
            Files.createDirectories(outDir());
            Path file = outDir().resolve(name + ".mp4");
            ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgba", "-s", w + "x" + h,
                "-r", String.valueOf(DemoHooks.FPS), "-i", "-", "-vf", "vflip", "-c:v", "libx264", "-preset", "veryfast", "-crf", "14", "-pix_fmt", "yuv420p", file.toString());
            pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(outDir().resolve(name + ".ffmpeg.log").toFile()));
            ff = pb.start();
            out = Channels.newChannel(ff.getOutputStream());
            buf = MemoryUtil.memAlloc(w * h * 4);
            csv = new PrintWriter(Files.newBufferedWriter(outDir().resolve(name + ".csv")));
            frames = 0; enabled = true; baseName = name;
            FpvClient.LOG.info("DEMO recording {} {}x{} -> {}", name, w, h, file);
        } catch (IOException e) { throw new RuntimeException(e); }
    }

    public static void stop() {
        if (!enabled) return;
        enabled = false;
        try {
            out.close(); ff.waitFor();
        } catch (Exception e) { FpvClient.LOG.warn("DEMO ffmpeg close: {}", e.toString()); }
        csv.close();
        MemoryUtil.memFree(buf);
        FpvClient.LOG.info("DEMO recording finished: {} frames ({} s)", frames, frames / (double) DemoHooks.FPS);
    }

    /** End of a rendered frame (before the blit to the window). */
    public static void onFrameRendered(Minecraft mc) {
        if (!DemoHooks.advanced) { return; }
        var lr = mc.levelRenderer;
        boolean complete = lr == null || lr.hasRenderedAllSections();
        if (!complete && holds < 240) { DemoHooks.frozen = true; holds++; return; }
        DemoHooks.frozen = false; holds = 0;
        if (!enabled) return;
        RenderTarget rt = mc.getMainRenderTarget();
        if (rt.width != w || rt.height != h) { FpvClient.LOG.warn("DEMO render target changed size"); return; }
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, rt.frameBufferId);
        buf.clear();
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
        buf.position(0).limit(w * h * 4);
        try {
            while (buf.hasRemaining()) out.write(buf);
        } catch (IOException e) { throw new RuntimeException("ffmpeg pipe closed", e); }
        if (csvRow != null) csv.println(frames + "," + csvRow.get());
        frames++;
    }
}
