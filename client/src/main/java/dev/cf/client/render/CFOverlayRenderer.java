package dev.cf.client.render;

import dev.cf.client.CFOverlayClient;
import dev.cf.client.CFGraphicsSettings;
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

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * World-space transparent areas with thick, explicit boundaries, plus route
 * ribbons. Uses Minecraft 26.2's Blaze3D StagedVertexBuffer instead of
 * direct OpenGL calls (compatible with the new renderer architecture).
 */
public final class CFOverlayRenderer {
    private static final RenderPipeline PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("castlefight", "overlay/colored_zones"))
                    .build());
    private static final StagedVertexBuffer BUFFER =
            new StagedVertexBuffer(() -> "Castle Fight overlay", 8*1024*1024);
    private static final Vector4f COLOR = new Vector4f(1,1,1,1);
    private static final Vector3f OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE = new Matrix4f();
    private static final int MAX_QUADS_PER_FRAME = 18_000;

    private CFOverlayRenderer() {}
    private static CFOverlayMesh extract;

    public static void register() {
        LevelExtractionEvents.END_EXTRACTION.register(CFOverlayRenderer::onExtract);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(CFOverlayRenderer::onRender);
    }
    private static void onExtract(LevelExtractionContext context) {
        // The network layer already prepared an IMMUTABLE geometry mesh once.
        extract = CFOverlayClient.currentMesh();
    }
    private static void onRender(LevelRenderContext context) {
        var mesh = extract;
        if (mesh == null || mesh.quads().isEmpty()) return;
        var settings=CFGraphicsSettings.current();
        float maxDistanceSq=settings.drawDistance()*settings.drawDistance();
        VertexFormat format = PIPELINE.getVertexFormatBinding(0);
        if (format == null) return;
        PrimitiveTopology primitive = PIPELINE.getPrimitiveTopology();
        var draw = BUFFER.appendDraw(format, primitive,
                primitive == PrimitiveTopology.QUADS ? RenderSystem.getProjectionType().vertexSorting() : null);
        PoseStack pose = context.poseStack();
        Vec3 camera = context.levelState().cameraRenderState.pos;
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        Matrix4fc matrix = pose.last().pose();
        VertexConsumer builder = BUFFER.getVertexBuilder(draw);
        int rendered = 0;
        for (var quad : mesh.near(camera.x,camera.z,(settings.drawDistance()+15)/16)) {
            float dx=(float)(quad.midX()-camera.x),dz=(float)(quad.midZ()-camera.z);
            if (dx*dx + dz*dz > maxDistanceSq) continue;
            put(builder,matrix,quad.ax(),quad.ay(),quad.az(),quad.rgb(),quad.alpha() * settings.opacityPercent()/100f);
            put(builder,matrix,quad.bx(),quad.by(),quad.bz(),quad.rgb(),quad.alpha() * settings.opacityPercent()/100f);
            put(builder,matrix,quad.cx(),quad.cy(),quad.cz(),quad.rgb(),quad.alpha() * settings.opacityPercent()/100f);
            put(builder,matrix,quad.dx(),quad.dy(),quad.dz(),quad.rgb(),quad.alpha() * settings.opacityPercent()/100f);
            if (++rendered >= MAX_QUADS_PER_FRAME) break;
        }
        pose.popPose();
        if (rendered == 0) {
            BUFFER.endFrame();
            return;
        }
        BUFFER.upload();
        StagedVertexBuffer.ExecuteInfo info = BUFFER.getExecuteInfo(draw);
        if (info != null) draw(Minecraft.getInstance(),info,PIPELINE);
        BUFFER.endFrame();
    }
    private static void put(VertexConsumer b,Matrix4fc m,float x,float y,float z,int rgb,float opacity) {
        b.addVertex(m,x,y,z).setColor(
                ((rgb>>16)&255)/255f,((rgb>>8)&255)/255f,(rgb&255)/255f,opacity);
    }
    private static void draw(Minecraft client,StagedVertexBuffer.ExecuteInfo info,RenderPipeline pipeline) {
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrixCopy(), COLOR, OFFSET, TEXTURE);
        RenderTarget target=client.gameRenderer.mainRenderTarget();
        GpuTextureView color=target.getColorTextureView();
        if (color == null) return;
        try (RenderPass pass=RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                ()->"Castle Fight map overlay",color,Optional.empty(),
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
