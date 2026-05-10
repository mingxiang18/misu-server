package com.misu.fileServer.audit;

/** 审计操作类型常量 */
public final class AuditAction {
    private AuditAction() {}

    public static final String UPLOAD_FILE = "UPLOAD_FILE";
    public static final String DELETE_FILE = "DELETE_FILE";
    public static final String MOVE_FILE = "MOVE_FILE";
    public static final String CREATE_DIR = "CREATE_DIR";
    public static final String BATCH_DELETE = "BATCH_DELETE";
    public static final String BATCH_MOVE = "BATCH_MOVE";
    public static final String RESTORE_TRASH = "RESTORE_TRASH";
    public static final String PURGE_TRASH = "PURGE_TRASH";
    public static final String SHARE_TO_PUBLIC = "SHARE_TO_PUBLIC";
    public static final String SHARE_CREATE = "SHARE_CREATE";
    public static final String SHARE_REVOKE = "SHARE_REVOKE";
    public static final String HASH_INSTANT_UPLOAD = "HASH_INSTANT_UPLOAD";
}
