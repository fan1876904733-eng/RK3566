package com.weiguang.drcontroller.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.weiguang.drcontroller.MainActivity;
import com.weiguang.drcontroller.R;
import com.weiguang.drcontroller.hardware.storage.CaseRepository;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HistoryFragment extends Fragment {

    private LinearLayout historyList;
    private android.widget.ScrollView historyScrollView;
    private int savedScrollY = 0;
    private TextView historyEmptyText;
    private TextView historyCountText;
    private Button historyRefreshButton;
    private final List<CaseRepository.CaseRecord> records = new ArrayList<>();
    private ExecutorService loaderExecutor;

    public HistoryFragment() {
        super(R.layout.fragment_history);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // 历史记录页面复用同一 Fragment 实例，视图重建时线程池可能已被 shutdown，
        // 此处按需重建，避免 RejectedExecutionException 导致整个 Activity 崩溃重启。
        if (loaderExecutor == null || loaderExecutor.isTerminated()) {
            loaderExecutor = Executors.newSingleThreadExecutor();
        }
        historyList = view.findViewById(R.id.historyList);
        historyScrollView = view.findViewById(R.id.historyScrollView);
        historyEmptyText = view.findViewById(R.id.historyEmptyText);
        historyCountText = view.findViewById(R.id.historyCountText);
        historyRefreshButton = view.findViewById(R.id.historyRefreshButton);
        historyRefreshButton.setOnClickListener(v -> loadHistory());
        // 先用上次缓存的列表立即渲染，避免返回时白屏/黑屏，后台再刷新最新数据。
        if (!records.isEmpty()) {
            renderRecords();
        }
        loadHistory();
    }

    public void refreshHistory() {
        loadHistory();
    }

    private void loadHistory() {
        if (!isAdded() || historyRefreshButton == null) return;
        historyRefreshButton.setEnabled(false);
        historyRefreshButton.setText("刷新中...");
        historyEmptyText.setVisibility(View.GONE);
        android.content.Context appContext = requireContext().getApplicationContext();
        loaderExecutor.execute(() -> {
            List<CaseRepository.CaseRecord> loaded = CaseRepository.queryCases(appContext, 120);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (historyList == null) return;
                records.clear();
                records.addAll(loaded);
                historyRefreshButton.setEnabled(true);
                historyRefreshButton.setText("刷新");
                renderRecords();
                // 列表渲染完成后再恢复滚动位置，否则此时高度为 0 滚动无效。
                if (historyScrollView != null && savedScrollY > 0) {
                    historyScrollView.post(() -> {
                        if (historyScrollView != null) {
                            historyScrollView.scrollTo(0, savedScrollY);
                        }
                    });
                }
            });
        });
    }

    private void renderRecords() {
        historyList.removeAllViews();
        historyCountText.setText("共 " + records.size() + " 个案例");
        if (records.isEmpty()) {
            historyEmptyText.setVisibility(View.VISIBLE);
            historyEmptyText.setText("暂无案例记录\n完成拍照后会按时间出现在这里");
            return;
        }
        historyEmptyText.setVisibility(View.GONE);
        for (CaseRepository.CaseRecord record : records) addCaseRow(record);
    }


    private void addCaseRow(CaseRepository.CaseRecord record) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(10), dp(11));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#162630"));
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), Color.parseColor("#294757"));
        row.setBackground(bg);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rowParams.bottomMargin = dp(8);
        historyList.addView(row, rowParams);

        LinearLayout info = new LinearLayout(requireContext());
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(info, infoParams);

        LinearLayout timeRow = new LinearLayout(requireContext());
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);

        boolean isCaseRead = requireContext().getSharedPreferences("weiguang_read", android.content.Context.MODE_PRIVATE).getBoolean(record.getCaseId(), false);
        boolean aiReportUnread = record.hasAiReport()
                && !CaseRepository.isAiReportRead(requireContext(), record.getCaseId());
        if (!isCaseRead || aiReportUnread) {
            View dot = new View(requireContext());
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
            dotParams.rightMargin = dp(8);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(Color.parseColor("#4FC3F7"));
            dot.setBackground(dotBg);
            timeRow.addView(dot, dotParams);
        }

        TextView time = new TextView(requireContext());
        time.setText(displayLabel(record));
        time.setTextColor(Color.WHITE);
        time.setTextSize(15f);
        time.setTypeface(time.getTypeface(), android.graphics.Typeface.BOLD);
        timeRow.addView(time);

        TextView spacer = new TextView(requireContext());
        LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(0, 1, 1f);
        timeRow.addView(spacer, spacerParams);
        info.addView(timeRow);

        TextView content = new TextView(requireContext());
        content.setText(buildContentSummary(record));
        content.setTextColor(Color.parseColor("#9FB3C1"));
        content.setTextSize(11f);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        contentParams.topMargin = dp(5);
        info.addView(content, contentParams);

        Button rename = makeButton("重命名", "#263844", 78);
        LinearLayout.LayoutParams renameParams = (LinearLayout.LayoutParams) rename.getLayoutParams();
        renameParams.leftMargin = dp(6);
        rename.setLayoutParams(renameParams);
        rename.setOnClickListener(v -> showRenameDialog(record));
        row.addView(rename);

        Button ai = makeButton(record.hasAiReport() ? "查看分析报告" : "AI分析：否", record.hasAiReport() ? "#1976D2" : "#263844", 118);
        LinearLayout.LayoutParams aiParams = (LinearLayout.LayoutParams) ai.getLayoutParams();
        aiParams.leftMargin = dp(6);
        ai.setLayoutParams(aiParams);
        ai.setOnClickListener(v -> handleAi(record));
        row.addView(ai);

        Button view = makeButton("查看图片", "#263844", 90);
        LinearLayout.LayoutParams viewParams = (LinearLayout.LayoutParams) view.getLayoutParams();
        viewParams.leftMargin = dp(6);
        view.setLayoutParams(viewParams);
        view.setOnClickListener(v -> {
            requireContext().getSharedPreferences("weiguang_read", android.content.Context.MODE_PRIVATE).edit().putBoolean(record.getCaseId(), true).apply();
            CaseRepository.markAiReportRead(requireContext(), record.getCaseId());
            renderRecords();
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).openCaseDetail(record.getCaseId());
            }
        });
        row.addView(view);

        Button delete = makeButton("删除", "#8E3B3B", 72);
        LinearLayout.LayoutParams deleteParams = (LinearLayout.LayoutParams) delete.getLayoutParams();
        deleteParams.leftMargin = dp(6);
        delete.setLayoutParams(deleteParams);
        delete.setOnClickListener(v -> confirmDelete(record));
        row.addView(delete);
    }

    private Button makeButton(String text, String color, int widthDp) {
        Button button = new Button(requireContext());
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(11f);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(7), 0, dp(7), 0);
        button.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor(color)));
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp), dp(40)));
        return button;
    }

    private void handleAi(CaseRepository.CaseRecord record) {
        if (record.hasAiReport()) {
            AlertDialog dialog = new AlertDialog.Builder(requireContext())
                    .setTitle("AI分析报告 · " + displayLabel(record))
                    .setMessage(record.getAiReport())
                    .setPositiveButton("关闭", null)
                    .create();
            dialog.setOnDismissListener(d -> {
                CaseRepository.markAiReportRead(requireContext(), record.getCaseId());
                renderRecords();
            });
            dialog.show();
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("尚未进行AI分析")
                .setMessage("是否对这个案例进行AI分析？")
                .setNegativeButton("否", null)
                .setPositiveButton("是", (dialog, which) -> {
                    if (getActivity() instanceof MainActivity) {
                        ((MainActivity) getActivity()).openAiForCase(record.getCaseId());
                    }
                })
                .show();
    }

    /** 案例名称：有自定义名显示自定义名，否则回退显示拍摄时间。 */
    private String displayLabel(CaseRepository.CaseRecord record) {
        if (record == null) return "未知案例";
        String name = record.getDisplayName();
        return (name == null || name.trim().isEmpty())
                ? formatTime(record.getTimeMillis())
                : name;
    }

    /** 重命名案例：弹输入框 → 保存自定义名称 → 刷新列表。 */
    private void showRenameDialog(CaseRepository.CaseRecord record) {
        EditText input = new EditText(requireContext());
        input.setHint("输入新名称（不超过20字）");
        input.setSingleLine(true);
        String current = record.getDisplayName();
        if (current != null) input.setText(current);
        new AlertDialog.Builder(requireContext())
                .setTitle("重命名案例")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    CaseRepository.setCaseName(
                            requireContext(),
                            record.getCaseId(),
                            input.getText() == null ? "" : input.getText().toString()
                    );
                    loadHistory();
                })
                .show();
    }

    private void confirmDelete(CaseRepository.CaseRecord record) {
        new AlertDialog.Builder(requireContext())
                .setTitle("删除案例")
                .setMessage("确定删除 " + displayLabel(record) + " 的全部影像、毫米波数据和AI报告吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> CaseRepository.deleteCase(
                        requireContext(),
                        record,
                        new CaseRepository.DeleteCallback() {
                            @Override public void onDeleted() {
                                if (!isAdded()) return;
                                Toast.makeText(requireContext(), "案例已删除", Toast.LENGTH_SHORT).show();
                                loadHistory();
                            }
                            @Override public void onError(String message) {
                                if (!isAdded()) return;
                                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                            }
                        }
                ))
                .show();
    }

    private String buildContentSummary(CaseRepository.CaseRecord record) {
        List<String> parts = new ArrayList<>();
        if (record.hasCameraImage()) parts.add("实时图");
        if (record.hasThermalImage()) parts.add("热成像图");
        if (record.hasXrayImage()) parts.add("X-Ray图");
        if (record.getRadarSnapshot() != null) parts.add("毫米波数据");
        return parts.isEmpty() ? "暂无影像内容" : android.text.TextUtils.join(" · ", parts);
    }

    private String formatTime(long timeMillis) {
        if (timeMillis <= 0L) return "时间未知";
        // 固定按北京时间显示，避免设备时区设置异常导致历史记录时间错误。
        SimpleDateFormat format = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.CHINA
        );
        format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return format.format(new Date(timeMillis));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroyView() {
        if (historyScrollView != null) {
            savedScrollY = historyScrollView.getScrollY();
        }
        historyList = null;
        historyScrollView = null;
        historyEmptyText = null;
        historyCountText = null;
        historyRefreshButton = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        loaderExecutor.shutdownNow();
        super.onDestroy();
    }
}
