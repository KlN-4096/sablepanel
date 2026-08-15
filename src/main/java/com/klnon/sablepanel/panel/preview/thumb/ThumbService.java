package com.klnon.sablepanel.panel.preview.thumb;

import com.klnon.sablepanel.panel.storage.DiskScanner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 缩略图缓存服务:渲染在浏览器 —— 与详情预览同一套 Three.js 管线,视觉天然一致。
 * 这里只管「该渲谁(内容签名)/收图校验/磁盘缓存」:首个看到某体的浏览器渲一张上传,
 * 之后所有人(含游戏内嵌页)直接吃缓存,常态零服务端渲染开销。
 * <ul>
 *   <li>GET 未命中时把当前签名当邀请函发给前端;前端渲完带签名 POST 回来,
 *       签名对得上才入缓存 —— 拒掉渲染期间体已变化的陈旧图;</li>
 *   <li>能上传的人 = 持 token 能看面板的人,同一信任域,内容只验 PNG 魔数与尺寸;</li>
 *   <li>too_large/副本歧义不再有服务端跳过名单:mesh 接口会当场拒绝,前端自己记住别再试。</li>
 * </ul>
 */
public final class ThumbService {
    /** 上传尺寸上限:640×480 的 PNG 实测几十 KB,512KB 已是数倍余量(网关另有 1MiB 总闸) */
    private static final int MAX_PNG_BYTES = 512 * 1024;
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G'};

    private final ThumbStore store;
    private final Supplier<List<DiskScanner.DiskEntry>> entries;

    public ThumbService(Path cacheDir, Supplier<List<DiskScanner.DiskEntry>> entries) throws IOException {
        this.store = new ThumbStore(cacheDir, ThumbStore.DEFAULT_MAX_BYTES);
        this.entries = entries;
    }

    /** 请求线程读:PNG 字节,没有返回 null */
    public byte[] read(UUID uuid) {
        return this.store.read(uuid);
    }

    /** 已缓存图入库时的内容签名;没图返回 null */
    public String cachedSig(UUID uuid) {
        return this.store.sig(uuid);
    }

    /** 该体此刻应有的内容签名;体不在盘上返回 null(回收站里的死体渲不了也不用渲) */
    public String currentSig(UUID uuid) {
        List<DiskScanner.DiskEntry> mine = new ArrayList<>();
        for (DiskScanner.DiskEntry entry : this.entries.get()) {
            if (uuid.equals(entry.uuid())) mine.add(entry);
        }
        return mine.isEmpty() ? null : signature(mine);
    }

    /**
     * 收前端渲好的图。
     *
     * @return null=收下;否则错误码(thumb_invalid=不是像样的 PNG,thumb_stale=签名对不上)
     */
    public String accept(UUID uuid, String sig, byte[] png) throws IOException {
        if (png == null || png.length < PNG_MAGIC.length || png.length > MAX_PNG_BYTES
                || !Arrays.equals(png, 0, PNG_MAGIC.length, PNG_MAGIC, 0, PNG_MAGIC.length)) {
            return "thumb_invalid";
        }
        String current = currentSig(uuid);
        if (sig == null || !sig.equals(current)) return "thumb_stale";
        this.store.put(uuid, sig, png);
        return null;
    }

    /**
     * 内容签名:渲染修订号 + 每条「槽位 id + 块数 + 方块实体数 + 内容物数 + 方块表哈希 + 取整包围盒」排序拼接。
     * 刻意不含坐标(飞船在飞,坐标永远在变)与名称(改名不改内容);副本增减会改变条数,天然触发重判。
     * 包围盒管的是"同一批方块重排"(阶梯改竖塔:块数方块表全同,只有尺寸变)——
     * 不含它时缩略图会永远停在旧形态(2026-08-14 凝灰岩矿场实测)。同盒内重排仍照不出来,先不管。
     * 修订号在渲染逻辑变化时拨一格,部署后签名全体失配 → 前端逐步重渲替换存量陈旧图
     * (r1~r3 = 服务端软光栅时代;f1 = 渲染上移浏览器,2026-08-15;
     *  f2 = 截帧相机按包围球拟合,小体不再缩成一粒,2026-08-15)。
     * 轴承角度不在 DiskEntry 里,角度变化不触发重渲,先不管。
     */
    static String signature(List<DiskScanner.DiskEntry> entries) {
        return entries.stream()
                .map(entry -> entry.key().id() + ":" + entry.blocks() + ":" + entry.blockEntities()
                        + ":" + entry.contents() + ":" + Integer.toHexString(entry.blockIds().hashCode())
                        + ":" + dims(entry.size()))
                .sorted()
                .collect(Collectors.joining("|", "f2|", ""));
    }

    private static String dims(double[] size) {
        if (size == null || size.length < 3) return "?";
        return Math.round(size[0]) + "x" + Math.round(size[1]) + "x" + Math.round(size[2]);
    }
}
