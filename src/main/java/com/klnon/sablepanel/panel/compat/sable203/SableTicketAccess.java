package com.klnon.sablepanel.panel.compat.sable203;

import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicket;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;

import java.lang.reflect.Method;

/** Sable 2.0.3 {@code getType()} / 2.0.4 {@code type()} 票访问器兼容。 */
public final class SableTicketAccess {
    private static final Method TYPE = resolveAccessor();

    private SableTicketAccess() {
    }

    public static boolean isType(SubLevelLoadingTicket<?> ticket, SubLevelLoadingTicketType<?> expected) {
        try {
            return expected.equals(TYPE.invoke(ticket));
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("读取常驻票类型失败", error);
        }
    }

    static Method resolveAccessor() {
        try {
            return SubLevelLoadingTicket.class.getMethod("type");
        } catch (NoSuchMethodException ignored) {
            try {
                return SubLevelLoadingTicket.class.getMethod("getType");
            } catch (NoSuchMethodException fatal) {
                throw new IllegalStateException("sable SubLevelLoadingTicket 缺少 type()/getType() 访问器", fatal);
            }
        }
    }
}
