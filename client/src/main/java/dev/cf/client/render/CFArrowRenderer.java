package dev.cf.client.render;

import dev.cf.protocol.CFShotWire;
import dev.cf.client.CFGraphicsSettings;
import net.minecraft.world.entity.Entity;
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
 * Draw varied client-owned projectile silhouettes without any real projectile entities.
 * One 9-byte event per shot contains entity IDs and a style byte only.
 * The server never transmits geometry, effect parameters or animations.
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
    // Flight geometry is resolved ONCE from client-visible Minecraft entities.
    // Not one coordinate, lifetime or color comes over the network.
    private record Flight(float fromX,float fromY,float fromZ,
                          float toX,float toY,float toZ,
                          int styleId,int flightTicks,long startedNanos) { }
    private static final List<Flight> active = new ArrayList<>();
    private static List<Flight> extracted = List.of();

    private CFArrowRenderer() { }

    public static void register() {
        LevelExtractionEvents.END_EXTRACTION.register(CFArrowRenderer::onExtract);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(CFArrowRenderer::onRender);
    }

    /** Network callback forwards to the main client thread before calling this. */
    public static void accept(CFShotWire.Shot shot) {
        var world = Minecraft.getInstance().level;
        if (world == null) return;
        Entity shooter = world.getEntity(shot.shooterId());
        Entity target = world.getEntity(shot.targetId());
        // An absent entity is a cosmetic-only miss: the server still deals damage.
        if (shooter == null || target == null) return;
        float sx=(float)shooter.getX();
        float sy=(float)(shooter.getY()+shooter.getEyeHeight()+0.12);
        float sz=(float)shooter.getZ();
        float tx=(float)target.getX();
        float ty=(float)(target.getY()+target.getBbHeight()*0.6);
        float tz=(float)target.getZ();
        double distance=Math.sqrt((tx-sx)*(tx-sx)+(ty-sy)*(ty-sy)+(tz-sz)*(tz-sz));
        if (!Double.isFinite(distance) || distance < .01 || distance > 96) return;
        int flightTicks=CFArrowMotion.ticksForDistance(distance);
        if (active.size() >= MAX_ACTIVE) active.remove(0);
        active.add(new Flight(sx,sy,sz,tx,ty,tz,shot.styleId(),flightTicks,System.nanoTime()));
    }

    public static void tick() {
        long now = System.nanoTime();
        active.removeIf(f -> CFArrowMotion.progress(Math.max(0,now-f.startedNanos()),
                f.flightTicks()) >= 1);
    }

    public static void clear() { active.clear(); extracted = List.of(); }

    private static void onExtract(LevelExtractionContext context) {
        extracted = active.isEmpty() ? List.of() : List.copyOf(active);
    }

    private static void onRender(LevelRenderContext context) {
        if (extracted.isEmpty() || CFGraphicsSettings.current().projectileQuality() == 0) return;
        var settings = CFGraphicsSettings.current();
        double maxViewSq = settings.drawDistance() * (double)settings.drawDistance();
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
            double progress=CFArrowMotion.progress(Math.max(0,now-flight.startedNanos()),flight.flightTicks());
            if (progress>=1) continue;
            double x=flight.fromX()+(flight.toX()-flight.fromX())*progress;
            double y=flight.fromY()+(flight.toY()-flight.fromY())*progress;
            double z=flight.fromZ()+(flight.toZ()-flight.fromZ())*progress;
            double cx=x-camera.x, cy=y-camera.y, cz=z-camera.z;
            if(cx*cx+cy*cy+cz*cz>maxViewSq) continue;
            drawProjectile(builder,matrix,(float)x,(float)y,(float)z,flight,settings);
            if(++count>=MAX_RENDERED) break;
        }
        pose.popPose();
        if(count==0) { BUFFER.endFrame(); return; }
        BUFFER.upload();
        StagedVertexBuffer.ExecuteInfo info=BUFFER.getExecuteInfo(command);
        if(info != null) draw(Minecraft.getInstance(),info,PIPELINE);
        BUFFER.endFrame();
    }

    /** Crossed ribbons: no textures or particles are transmitted by the server. */
    private static void drawProjectile(VertexConsumer builder, Matrix4fc matrix,
                                       float x,float y,float z,Flight flight,
                                       CFGraphicsSettings.Options options) {
        var style = CFProjectilePalette.forId(flight.styleId());
        float dx=flight.toX()-flight.fromX(),dy=flight.toY()-flight.fromY(),dz=flight.toZ()-flight.fromZ();
        float norm=(float)Math.sqrt(dx*dx+dy*dy+dz*dz);
        if(norm<0.01f) return;
        dx/=norm;dy/=norm;dz/=norm;
        float vx=-dz, vz=dx;
        float h=(float)Math.hypot(vx,vz), vy=0;
        if(h<.01f) {vx=1;vz=0;} else {vx/=h;vz/=h;}
        float wx=dy*vz-dz*vy, wy=dz*vx-dx*vz, wz=dx*vy-dy*vx;
        for(int side=0;side<2;side++) {
            float ox=side==0?vx:wx, oy=side==0?vy:wy, oz=side==0?vz:wz;
            float len=style.length(), radius=style.radius();
            switch(style.shape()) {
                case ARROW -> {
                    ribbon(builder,matrix,x,y,z,dx,dy,dz,ox,oy,oz,
                            -len*.65f,len*.12f,radius*.44f,style.primary());
                    ribbon(builder,matrix,x,y,z,dx,dy,dz,ox,oy,oz,
                            len*.11f,len*.42f,radius,style.accent());
                    ribbon(builder,matrix,x,y,z,dx,dy,dz,ox,oy,oz,
                            -len*.82f,-len*.56f,radius*1.4f,style.accent());
                }
                case BOLT -> {
                    ribbon(builder,matrix,x,y,z,dx,dy,dz,ox,oy,oz,
                            -len*.6f,len*.5f,radius,style.primary());
                    ribbon(builder,matrix,x,y,z,dx,dy,dz,ox,oy,oz,
                            len*.09f,len*.61f,radius*.48f,style.accent());
                }
                case ORB, SNOWBALL -> {
                    ribbon(builder,matrix,x,y,z,dx,dy,dz,ox,oy,oz,
                            -len,len,radius,style.primary());
                    ribbon(builder,matrix,x,y,z,dx,dy,dz,ox,oy,oz,
                            -len*.65f,len*.65f,radius*.46f,style.accent());
                }
                case SHARD -> {
                    ribbon(builder,matrix,x,y,z,dx,dy,dz,ox,oy,oz,
                            -len*.45f,len*.42f,radius,style.primary());
                    ribbon(builder,matrix,x,y,z,dx,dy,dz,ox,oy,oz,
                            len*.23f,len*.65f,radius*.26f,style.accent());
                }
            }
            // Optional small LOCAL trail. Always bounded; no extra network event.
            if(options.trails() && options.projectileQuality() == 2 &&
                    style.shape() != CFProjectilePalette.Shape.SNOWBALL) {
                ribbon(builder,matrix,x,y,z,dx,dy,dz,ox,oy,oz,
                        -len*1.35f,-len*.96f,radius*.52f,style.primary());
                ribbon(builder,matrix,x,y,z,dx,dy,dz,ox,oy,oz,
                        -len*1.85f,-len*1.56f,radius*.27f,style.accent());
            }
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
