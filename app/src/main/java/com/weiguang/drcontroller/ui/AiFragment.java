package com.weiguang.drcontroller.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.weiguang.drcontroller.R;
import com.weiguang.drcontroller.hardware.ai.AiAnalyzer;
import com.weiguang.drcontroller.hardware.storage.CaseRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AiFragment extends Fragment {

    private static final String ARG_CASE_ID = "case_id";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<CaseRepository.CaseRecord> cases = new ArrayList<>();
    private Spinner caseSpinner;
    private TextView caseSummaryText;
    private TextView reportText;
    private Button analyzeButton;
    private String requestedCaseId;
    private boolean analyzing = false;

    public AiFragment() {
        super(R.layout.fragment_ai);
    }

    public static AiFragment newInstance(String caseId) {
        AiFragment fragment = new AiFragment();
        Bundle args = new Bundle();
        args.putString(ARG_CASE_ID, caseId);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        caseSpinner = view.findViewById(R.id.aiCaseSpinner);
        caseSummaryText = view.findViewById(R.id.aiCaseSummaryText);
        reportText = view.findViewById(R.id.aiReportText);
        analyzeButton = view.findViewById(R.id.aiAnalyzeButton);
        requestedCaseId = getArguments() == null ? null : getArguments().getString(ARG_CASE_ID);

        caseSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View selectedView, int position, long id) {
                renderSelectedCase(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        analyzeButton.setOnClickListener(v -> analyzeSelectedCase());
        loadCases();
    }

    private void loadCases() {
        Context appContext = requireContext().getApplicationContext();
        executor.execute(() -> {
            List<CaseRepository.CaseRecord> loaded = CaseRepository.queryCases(appContext, 120);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> bindCases(loaded));
        });
    }

    private void bindCases(List<CaseRepository.CaseRecord> loaded) {
        cases.clear();
        if (loaded != null) cases.addAll(loaded);

        List<String> labels = new ArrayList<>();
        for (CaseRepository.CaseRecord record : cases) {
            String name = record.getDisplayName() == null || record.getDisplayName().trim().isEmpty()
                    ? formatTime(record.getTimeMillis())
                    : record.getDisplayName();
            String label = name + "  ·  " + modalitySummary(record)
                    + (record.hasAiReport() ? "  ·  已完成AI分析" : "");
            labels.add(label);
        }
        if (labels.isEmpty()) {
            labels.add("暂无历史案例");
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_item,
                labels
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        caseSpinner.setAdapter(adapter);
        analyzeButton.setEnabled(!cases.isEmpty());

        if (cases.isEmpty()) {
            caseSummaryText.setText("还没有可分析的历史案例，请先完成拍摄。\n历史记录会按时间自动保存案例。 ");
            reportText.setText("暂无分析报告。 ");
            return;
        }

        int selectedIndex = 0;
        if (requestedCaseId != null) {
            for (int i = 0; i < cases.size(); i++) {
                if (requestedCaseId.equals(cases.get(i).getCaseId())) {
                    selectedIndex = i;
                    break;
                }
            }
        }
        caseSpinner.setSelection(selectedIndex);
        renderSelectedCase(selectedIndex);
    }

    private void renderSelectedCase(int position) {
        if (position < 0 || position >= cases.size()) {
            analyzeButton.setEnabled(false);
            return;
        }
        CaseRepository.CaseRecord record = cases.get(position);
        CaseRepository.RadarSnapshot radar = record.getRadarSnapshot();
        String radarState = radar == null
                ? "暂无毫米波数据"
                : (radar.isConnected() ? "毫米波数据已记录" : "毫米波数据已记录（拍摄时待连接）");

        caseSummaryText.setText(
                (record.getDisplayName() == null || record.getDisplayName().trim().isEmpty()
                        ? "拍摄时间：" + formatTime(record.getTimeMillis())
                        : "案例名称：" + record.getDisplayName()) +
                "\n影像：" + modalitySummary(record) +
                "\n数据：" + radarState +
                "\nAI状态：" + (record.hasAiReport() ? "已分析" : "未分析")
        );
        reportText.setText(record.hasAiReport()
                ? record.getAiReport()
                : "该案例尚未进行AI分析。点击“开始AI分析”后，报告会自动保存到历史记录。 ");
        analyzeButton.setText(record.hasAiReport() ? "重新AI分析" : "开始AI分析");
        analyzeButton.setEnabled(true);
    }

    /**
     * 发起 AI 分析：读取案例影像（可见光/热成像/X-Ray）→ 压缩为 base64 →
     * 调用 DeepSeek 多模态模型（deepseek-flash）看图分析 → 报告保存到历史记录。
     * 已有历史报告时点击按钮会重新分析并覆盖保存。
     */
    private void analyzeSelectedCase() {
        if (analyzing) return;
        int position = caseSpinner.getSelectedItemPosition();
        if (position < 0 || position >= cases.size()) return;
        CaseRepository.CaseRecord record = cases.get(position);

        analyzing = true;
        analyzeButton.setEnabled(false);
        analyzeButton.setText("分析中...");
        reportText.setText("正在读取案例影像并调用 AI 多模态模型分析，请稍候...");

        Context appContext = requireContext().getApplicationContext();
        AiAnalyzer.analyzeCase(appContext, record, null, new AiAnalyzer.Callback() {
            @Override
            public void onSuccess(String reply) {
                analyzing = false;
                if (!isAdded()) return;
                reportText.setText(reply);
                analyzeButton.setEnabled(true);
                analyzeButton.setText("重新AI分析");
                Toast.makeText(requireContext(), "分析报告已生成并保存到历史记录", Toast.LENGTH_LONG).show();
                loadCases();
            }

            @Override
            public void onError(String error) {
                analyzing = false;
                if (!isAdded()) return;
                reportText.setText("AI分析失败：" + error + "\n\n请检查设备网络后重试。");
                analyzeButton.setEnabled(true);
                analyzeButton.setText(record.hasAiReport() ? "重新AI分析" : "开始AI分析");
                Toast.makeText(requireContext(), "AI分析失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String modalitySummary(CaseRepository.CaseRecord record) {
        List<String> parts = new ArrayList<>();
        if (record.hasCameraImage()) parts.add("实时图");
        if (record.hasThermalImage()) parts.add("热成像图");
        if (record.hasXrayImage()) parts.add("X-Ray图");
        return parts.isEmpty() ? "暂无影像" : join(parts);
    }

    private String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) builder.append("、");
            builder.append(value);
        }
        return builder.toString();
    }

    private String formatTime(long timeMillis) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
                .format(new java.util.Date(timeMillis > 0L ? timeMillis : System.currentTimeMillis()));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
