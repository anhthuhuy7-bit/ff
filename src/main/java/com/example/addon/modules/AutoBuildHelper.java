package com.example.addon.modules;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Helper cho AutoBuild:
 *  - di chuyển bằng Baritone (GoalNear)
 *  - đặt block bằng interactBlock (cách Baritone dùng: gửi PlayerInteractBlockC2SPacket
 *    kèm sequence qua interactionManager), có xoay đầu + sneak tùy chọn.
 *
 * CHƯA BIÊN DỊCH / CHƯA CHẠY THỬ. Tên class theo Yarn; nếu build lỗi ở
 * PlayerMoveC2SPacket.LookAndOnGround (số tham số) thì chỉnh theo bản 1.21.11.
 */
public final class AutoBuildHelper {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private AutoBuildHelper() {}

    // ---------------------------------------------------------------- di chuyển

    private static IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    /** Đi tới gần target (trong vòng `reach` block). Gọi mỗi tick được, không spam path mới. */
    public static void moveNear(BlockPos target, double reach) {
        IBaritone b = baritone();
        if (b.getPathingBehavior().isPathing()) return; // đang đi rồi
        b.getCustomGoalProcess().setGoalAndPath(new GoalNear(target, Math.max(1, (int) reach)));
    }

    public static boolean isMoving() {
        return baritone().getPathingBehavior().isPathing();
    }

    /** Gọi khi module bị tắt hoặc khi đã vào tầm đặt block. */
    public static void stopMoving() {
        baritone().getPathingBehavior().cancelEverything();
    }

    public static boolean inReach(BlockPos pos, double range) {
        ClientPlayerEntity p = mc.player;
        if (p == null) return false;
        return p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) <= range * range;
    }

    // ---------------------------------------------------------------- đặt block

    /**
     * Đặt block tại pos (block đang cầm trên tay chính).
     * Tìm 1 block hàng xóm đặc để đặt lên. Trả về true nếu đã gửi lệnh đặt.
     *
     * @param rotate  gửi packet xoay đầu về phía điểm click trước khi đặt
     * @param sneak   giữ sneak trong lúc đặt (cần khi click vào rương/lò/nút...)
     */
    public static boolean placeBlock(BlockPos pos, boolean rotate, boolean sneak) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return false;
        if (!mc.world.getBlockState(pos).isReplaceable()) return false;

        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.offset(dir);
            if (mc.world.getBlockState(neighbor).isReplaceable()) continue; // cần block có điểm tựa

            Direction face = dir.getOpposite(); // mặt của neighbor hướng về pos
            Vec3d hit = Vec3d.ofCenter(neighbor).add(Vec3d.of(face.getVector()).multiply(0.5));
            BlockHitResult result = new BlockHitResult(hit, face, neighbor, false);

            if (rotate) sendRotation(hit);
            if (sneak) sendSneak(true);

            ActionResult r = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, result);
            if (r.isAccepted()) mc.player.swingHand(Hand.MAIN_HAND);

            if (sneak) sendSneak(false);
            return r.isAccepted();
        }
        return false; // không có điểm tựa
    }

    // ---------------------------------------------------------------- packet phụ

    /** Xoay đầu phía server (rotation spoof), không đổi góc nhìn client. */
    public static void sendRotation(Vec3d target) {
        ClientPlayerEntity p = mc.player;
        Vec3d eye = p.getEyePos();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
        float pitch = (float) -(MathHelper.atan2(dy, dist) * 180.0 / Math.PI);
        // 1.21.2+: LookAndOnGround(yaw, pitch, onGround, horizontalCollision)
        mc.getNetworkHandler().sendPacket(
            new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, p.isOnGround(), p.horizontalCollision));
    }

    public static void sendSneak(boolean on) {
        mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(
            mc.player,
            on ? ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY : ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY));
    }
}
