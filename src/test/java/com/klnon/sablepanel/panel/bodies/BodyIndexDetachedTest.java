package com.klnon.sablepanel.panel.bodies;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 断链残骸判定。这条判据要交给删除,判错就是直接删掉玩家的东西 ——
 * 守住三件事:主体分量的边界、量不出包围盒时必须放过、以及"离得远"不等于"该删"的反面
 * (贴着主体但没接触到的体,只要在容差内就算连着)。
 */
class BodyIndexDetachedTest {
    /** 底面中心 + 尺寸 → 面板语义的一个体 */
    private record Body(UUID uuid, double[] pos, double[] size) {
    }

    private final Map<UUID, Body> world = new HashMap<>();
    /** 坐标存疑的体:同一 uuid 有多份位置不同的存盘条目 */
    private final Set<UUID> ambiguous = new java.util.HashSet<>();

    private Body at(double x, double y, double z, double w, double h, double d) {
        Body b = new Body(UUID.randomUUID(), new double[]{x, y, z}, new double[]{w, h, d});
        this.world.put(b.uuid(), b);
        return b;
    }

    private Body ambiguous(Body b) {
        this.ambiguous.add(b.uuid());
        return b;
    }

    private Set<UUID> detached(Body... hubFirst) {
        List<UUID> members = new ArrayList<>();
        for (Body b : hubFirst) members.add(b.uuid());
        return BodyIndex.detachedMembers(members,
                u -> BodyIndex.boxOf(this.world.get(u).pos(), this.world.get(u).size()),
                u -> !this.ambiguous.contains(u));
    }

    /** 20×20×20 的主体放在原点,底面 y=0 */
    private Body hub() {
        return at(0, 0, 0, 20, 20, 20);
    }

    @Test
    void aBodyTouchingTheHubStaysAndOneHundredsOfBlocksAwayIsDetached() {
        Body hub = hub();
        Body attached = at(14, 5, 0, 8, 4, 4);   // x 跨 [10,18],贴着主体的 x=10 面
        Body debris = at(250, 100, 0, 3, 3, 3);

        assertEquals(Set.of(debris.uuid()), detached(hub, attached, debris));
    }

    /**
     * 残骸自己抱成一团也不算数:判据是"连不连得上主体",不是"孤不孤单"。
     * 实测糖音气球那 173 个就是三摊各自成团的。
     */
    @Test
    void aClusterOfDebrisIsStillDebrisEvenThoughItsMembersTouchEachOther() {
        Body hub = hub();
        Body far1 = at(250, 100, 0, 4, 4, 4);
        Body far2 = at(253, 100, 0, 4, 4, 4);   // 和 far1 相交,但整团离主体 200+ 格
        Body far3 = at(256, 100, 0, 4, 4, 4);

        assertEquals(Set.of(far1.uuid(), far2.uuid(), far3.uuid()), detached(hub, far1, far2, far3));
    }

    /** 链式相连:A 贴主体、B 贴 A、C 贴 B —— 整条都算挂在主体上 */
    @Test
    void connectivityIsTransitiveAlongAChainOfTouchingBodies() {
        Body hub = hub();
        Body a = at(14, 5, 0, 8, 4, 4);     // [10,18]
        Body b = at(22, 5, 0, 8, 4, 4);     // [18,26]
        Body c = at(30, 5, 0, 8, 4, 4);     // [26,34]

        assertTrue(detached(hub, a, b, c).isEmpty());
    }

    /**
     * 8 格容差的两侧。轴承本来就有间隙,包围盒又是存盘快照,不能要求严丝合缝;
     * 但容差一旦被调大到能跨过真实缺口,残骸就会被当成好体留下来 —— 边界要钉住。
     * 主体 x 到 10 为止,所以间隙 = 对方 minX - 10。
     */
    @Test
    void theToleranceBoundaryIsEightBlocks() {
        Body hub = hub();
        Body gapEight = at(20, 5, 0, 4, 4, 4);   // x [18,22] → 间隙 8,算连着
        assertTrue(detached(hub, gapEight).isEmpty());

        Body gapNine = at(21, 5, 0, 4, 4, 4);    // x [19,23] → 间隙 9,断了
        assertEquals(Set.of(gapNine.uuid()), detached(hub, gapNine));
    }

    /**
     * 包围盒量不出来(NaN/无穷)时必须判成"连着"。这条反过来才是危险的:
     * 一个坏掉的 world_bounds 会让好体被当成残骸删掉。
     */
    @Test
    void aBodyWithAnUnmeasurableBoxIsNeverReportedAsDebris() {
        Body hub = hub();
        Body broken = at(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        Body infinite = at(0, 0, 0, Double.POSITIVE_INFINITY, 1, 1);

        assertTrue(detached(hub, broken, infinite).isEmpty(), "量不出包围盒就不判,宁可漏");
    }

    /** 单成员组没有"主体之外"可言 */
    @Test
    void aSingleMemberGroupHasNothingToDetach() {
        assertTrue(detached(hub()).isEmpty());
    }

    /**
     * 坐标存疑的成员永远不报残骸。实测糖音气球的 6 个推进器:存盘条目有 2~4 份,
     * 挑中的那份把它们放在 1300 格外,加载后其实就贴在本体旁边 ——
     * 当时是被副本冲突的闸门偶然挡下来的,判据自己必须挡住这一类。
     */
    @Test
    void aMemberWhoseSavedCopiesDisagreeOnPositionIsNeverReportedAsDebris() {
        Body hub = hub();
        Body thruster = ambiguous(at(1300, 100, 0, 4, 4, 4));
        Body debris = at(1300, 100, 40, 4, 4, 4);   // 同样远,但只有一份存档

        assertEquals(Set.of(debris.uuid()), detached(hub, thruster, debris));
    }

    /**
     * 坐标存疑的成员也不能当中转站:它退出几何比较,不会把真残骸拉进主体分量。
     * 否则一份错坐标能顺手赦免它周围的一整摊。
     */
    @Test
    void anUntrustedMemberDoesNotBridgeDebrisBackToTheHub() {
        Body hub = hub();
        Body bridge = ambiguous(at(14, 5, 0, 8, 4, 4));   // 假坐标 [10,18],正好贴着主体
        Body debris = at(24, 5, 0, 8, 4, 4);              // [20,28]:离 bridge 2 格,离主体 10 格

        assertEquals(Set.of(debris.uuid()), detached(hub, bridge, debris));
    }

    /**
     * 主体是参照系,坐标存疑也照样参与比较 —— 否则整组都连不上它,192 个体会一起被判成残骸。
     * 这种情况另有 detach_unsure 标记告诉用户判定不牢靠。
     */
    @Test
    void anAmbiguousHubStillAnchorsTheJudgement() {
        Body hub = ambiguous(hub());
        Body attached = at(14, 5, 0, 8, 4, 4);
        Body debris = at(250, 100, 0, 3, 3, 3);

        assertEquals(Set.of(debris.uuid()), detached(hub, attached, debris));
    }

    /** 主体的包围盒量不出来时整组不判:NaN 比较处处为 false,不拦住就是全组判成残骸 */
    @Test
    void anUnmeasurableHubDisablesTheJudgementForTheWholeGroup() {
        Body hub = at(Double.NaN, 0, 0, 20, 20, 20);
        Body debris = at(250, 100, 0, 3, 3, 3);

        assertTrue(detached(hub, debris).isEmpty(), "参照系没了就不判,宁可漏");
    }
}
