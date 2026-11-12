package dev.cf.client.render;

import dev.cf.protocol.CFShotWire;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Render actual arrow-shaped crossed quads, animated entirely in client RAM.
 * Server sends a single bounded launch snapshot; no tick packets and no damage
 * are calculated here. No modification to Minecraft's real world entities.
 */
public final class CFArrowRenderer {
    private static final RenderPipeline PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("castlefight", "arrows/flight"))
                    .build());
    private static final StagedVertexBuffer BUFFER =
            new StagedVertexBuffer(() -> "Castle Fight client arrows", 2*1024*1024);
    private static final Vector4f WHITE = new Vector4f(1,1,1,1);
    private static final Vector3f OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE = new Matrix4f();
    private static final int MAX_ACTIVE = 512;
    private static final int MAX_RENDERED = 256;
    private static final double MAX_VIEW_SQ = 96.0 * 96.0;
    private static final int SHAFT = 0xAC7943, TIP = 0xD0D9DF;
    private static final int FLETCH_BLUE = 0x69AEF4, FLETCH_RED = 0xF18C83;

    private record Flight(CFShotWire.Shot shot, long startedNanos) { }
    private static final List<Flight> active = new ArrayList<>();
    private static List<Flight> extracted = List.of();

    private CFArrowRenderer() { }

    public static void register() {
        LevelExtractionEvents.END_EXTRACTION.register(CFArrowRenderer::onExtract);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(CFArrowRenderer::onRender);
    }

    /** Network callback forwards to the main client thread before calling this. */
    public static void accept(CFShotWire.Shot shot) {
        if (active.size() >= MAX_ACTIVE) active.remove(0);
        active.add(new Flight(shot, System.nanoTime()));
    }

    public static void tick() {
        long now = System.nanoTime();
        active.removeIf(f -> CFArrowMotion.progress(Math.max(0,now - f.startedNanos),
                f.shot.flightTicks()) >= 1);
    }

    public static void clear() { active.clear(); extracted = List.of(); }

    private static void onExtract(LevelExtractionContext context) {
        extracted = active.isEmpty() ? List.of() : List.copyOf(active);
    }

    private static void onRender(LevelRenderContext context) {
        if (extracted.isEmpty()) return;
        VertexFormat format = PIPELINE.getVertexFormatBinding(0);
        if (format == null) return;
        PrimitiveTopology primitive = PIPELINE.getPrimitiveTopology();
        var command = BUFFER.appendDraw(format, primitive,
                primitive == PrimitiveTopology.QUADS ? RenderSystem.getProjectionType().vertexSorting() : null);
        VertexConsumer builder = BUFFER.getVertexBuilder(command);
        PoseStack pose = context.poseStack();
        Vec3 camera = context.levelState().cameraRenderState.pos;
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        Matrix4fc matrix = pose.last().pose();
        long now = System.nanoTime();
        int count=0;
        for (Flight flight : extracted) {
            CFShotWire.Shot shot = flight.shot();
            double progress=CFArrowMotion.progress(Math.max(0,now-flight.startedNanos()),shot.flightTicks());
            if (progress>=1) continue;
            double x=shot.fromX()+(shot.toX()-shot.fromX())*progress;
            double y=shot.fromY()+(shot.toY()-shot.fromY())*progress;
            double z=shot.fromZ()+(shot.toZ()-shot.fromZ())*progress;
            double cx=x-camera.x, cy=y-camera.y, cz=z-camera.z;
            if(cx*cx+cy*cy+cz*cz>MAX_VIEW_SQ) continue;
            drawArrow(builder,matrix,(float)x,(float)y,(float)z,shot);
            if(++count>=MAX_RENDERED) break;
        }
        pose.popPose();
        if(count==0) { BUFFER.endFrame(); return; }
        BUFFER.upload();
        StagedVertexBuffer.ExecuteInfo info=BUFFER.getExecuteInfo(command);
        if(info != null) draw(Minecraft.getInstance(),info,PIPELINE);
        BUFFER.endFrame();
    }

    private static void drawArrow(VertexConsumer builder, Matrix4fc matrix,
                                  float x,float y,float z,CFShotWire.Shot shot) {
        float dx=shot.toX()-shot.fromX(),dy=shot.toY()-shot.fromY(),dz=shot.toZ()-shot.fromZ();
        float norm=(float)Math.sqrt(dx*dx+dy*dy+dz*dz);
        if(norm<0.01f) return;
        dx/=norm;dy/=norm;dz/=norm;
        // Two perpendicular crossed faces give the arrow a visible silhouette
        // from either side without spawning real entities or requiring textures.
        float vx=-dz,vz=dx;
        float h=(float)Math.hypot(vx,vz);
        float vy=0;
        if(h<0.01f) {vx=1;vz=0;}
        else {vx/=h;vz/=h;}
        float wx=dy*vz-dz*vy,wy=dz*vx-dx*vz,wz=dx*vy-dy*vx;
        for(int side=0; side<2; side++) {
            float ox=side==0?vx:wx,oy=side==0?vy:wy,oz=side==0?vz:wz;
            ribbon(builder,matrix,x,y,z,dx,dy,dz,ox,oy,oz,-.38f,.06f,.022f,SHAFT);
            ribbon(builder,matrix,x,y,z,dx,dy,dz,ox,oy,oz,.04f,.24f,.047f,TIP);
            ribbon(builder,matrix,x,y,z,dx,dy,dz,ox,oy,oz,-.43f,-.25f,.060f,
                    shot.blue()?FLETCH_BLUE:FLETCH_RED);
        }
    }

    private static void ribbon(VertexConsumer b,Matrix4fc m,float x,float y,float z,
                               float dx,float dy,float dz,float ox,float oy,float oz,
                               float start,float end,float width,int rgb) {
        float sx=x+dx*start,sy=y+dy*start,sz=z+dz*start;
        float ex=x+dx*end,ey=y+dy*end,ez=z+dz*end;
        vertex(b,m,sx+ox*width,sy+oy*width,sz+oz*width,rgb);
        vertex(b,m,ex+ox*width,ey+oy*width,ez+oz*width,rgb);
        vertex(b,m,ex-ox*width,ey-oy*width,ez-oz*width,rgb);
        vertex(b,m,sx-ox*width,sy-oy*width,sz-oz*width,rgb);
    }
    private static void vertex(VertexConsumer b,Matrix4fc m,float x,float y,float z,int rgb) {
        b.addVertex(m,x,y,z).setColor((rgb>>16&255)/255f,
                (rgb>>8&255)/255f,(rgb&255)/255f,1f);
    }
    private static void draw(Minecraft client,StagedVertexBuffer.ExecuteInfo info,RenderPipeline pipeline) {
        GpuBufferSlice transforms=RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrixCopy(),WHITE,OFFSET,TEXTURE);
        RenderTarget target=client.gameRenderer.mainRenderTarget();
        GpuTextureView color=target.getColorTextureView();
        if(color==null) return;
        try(RenderPass pass=RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                ()->"Castle Fight client arrows",color,Optional.empty(),
                target.getDepthTextureView(),OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms",transforms);
            pass.setVertexBuffer(0,info.vertexBuffer().slice());
            pass.setIndexBuffer(info.indexBuffer(),info.indexType());
            pass.drawIndexed(info.indexCount(),1,info.firstIndex(),info.baseVertex(),0);
        }
    }
    public static void close() { BUFFER.close(); }
}
