package com.tungph7.learnpostgres.feature.dashboard.service;

/** Kết quả một lần {@link DashboardService#refreshDailyRevenue()}. */
public enum RefreshOutcome {

  /** {@code REFRESH ... CONCURRENTLY} — dashboard vẫn đọc bản cũ trong lúc chạy. */
  REFRESHED,

  /**
   * MV đang chưa có dữ liệu ({@code REFRESH ... WITH NO DATA}) nên {@code CONCURRENTLY} không chạy
   * được — đã nạp bằng {@code REFRESH} thường. Lần sau quay lại {@link #REFRESHED}.
   */
  POPULATED,

  /** Transaction khác (instance khác của app) đang refresh — lần này không làm gì. */
  SKIPPED
}
