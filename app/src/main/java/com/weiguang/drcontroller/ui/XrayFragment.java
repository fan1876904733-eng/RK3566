package com.weiguang.drcontroller.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.weiguang.drcontroller.R;
import com.weiguang.drcontroller.hardware.storage.CaseRepository;
import com.weiguang.drcontroller.hardware.storage.ImageRepository;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * X-Ray 历史查看页。
 *
 * 当前页面不再负责连接 X-Ray 主机，只负责：
 * 1. 从历史记录中列出所有包含 X-Ray 影像的案例；
 * 2. 显示所选案例的 X-Ray 图；
 * 3. 在右侧显示该案例已保存的 AI 分析结果；
 * 4. 返回选择界面并继续选择其他 X-Ray 案例。
 */
public class XrayFragment extends Fragment {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ImageView xrayImageView;
    private View xrayBlackMask;
    private View xrayCenterPanel;
    private TextView xrayCenterHintText;
    private TextView xrayStatusText;
    private TextView xrayCaptureTimeText;
    private TextView xrayAiResultText;
    private Button xrayHistoryButton;
    private Button xrayHistoryCornerButton;

    public XrayFragment() {
        super(R.layout.fragment_xray);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        xrayImageView = view.findViewById(R.id.xrayImageView);
        xrayBlackMask = view.findViewById(R.id.xrayBlackMask);
        xrayCenterPanel = view.findViewById(R.id.xrayCenterPanel);
        xrayCenterHintText = view.findViewById(R.id.xrayCenterHintText);
        xrayStatusText = view.findViewById(R.id.xrayStatusText);
        xrayCaptureTimeText = view.findViewById(R.id.xrayCaptureTimeText);
        xrayAiResultText = view.findViewById(R.id.xrayAiResultText);
        xrayHistoryButton = view.findViewById(R.id.xrayHistoryButton);
        xrayHistoryCornerButton = view.findViewById(R.id.xrayHistoryCornerButton);

        xrayHistoryButton.setOnClickListener(v -> showHistoryXrayDialog());
        xrayHistoryCornerButton.setOnClickListener(v -> showSelectionState());

        showSelectionState();
    }

    private void showSelectionState() {
        if (xrayImageView == null) return;
        xrayImageView.setImageDrawable(null);
        xrayBlackMask.setVisibility(View.VISIBLE);
        xrayCenterPanel.setVisibility(View.VISIBLE);
        xrayCenterHintText.setText("选择历史案例");
        xrayHistoryButton.setVisibility(View.VISIBLE);
        xrayHistoryCornerButton.setVisibility(View.GONE);
        xrayCaptureTimeText.setText("案例时间：--");
        xrayStatusText.setText("● X-Ray影像查看");
        xrayAiResultText.setText("请选择一个历史案例查看对应的AI分析结果。");
    }

    private void showHistoryXrayDialog() {
        Context appContext = requireContext().getApplicationContext();
        executor.execute(() -> {
            List<CaseRepository.CaseRecord> allCases = CaseRepository.queryCases(appContext, 200);
            List<CaseRepository.CaseRecord> xrayCases = new ArrayList<>();
            for (CaseRepository.CaseRecord record : allCases) {
                if (record != null && record.hasXrayImage()) xrayCases.add(record);
            }

            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (xrayCases.isEmpty()) {
                    Toast.makeText(requireContext(), "历史记录中还没有案例", Toast.LENGTH_SHORT).show();
                    return;
                }

                String[] labels = new String[xrayCases.size()];
                for (int i = 0; i < xrayCases.size(); i++) {
                    CaseRepository.CaseRecord record = xrayCases.get(i);
                    labels[i] = formatTime(record.getTimeMillis()) + "  ·  " + record.getCaseId();
                }

                new AlertDialog.Builder(requireContext())
                        .setTitle("选择查看X-Ray图")
                        .setItems(labels, (dialog, which) -> displayHistoricalXray(xrayCases.get(which)))
                        .setNegativeButton("取消", null)
                        .show();
            });
        });
    }

    private void displayHistoricalXray(CaseRepository.CaseRecord record) {
        if (record == null || getView() == null) return;

        xrayBlackMask.setVisibility(View.GONE);
        xrayCenterPanel.setVisibility(View.GONE);
        xrayHistoryCornerButton.setVisibility(View.VISIBLE);
        xrayCaptureTimeText.setText("案例时间：" + formatTime(record.getTimeMillis()));
        xrayStatusText.setText("● 正在查看X-Ray影像");

        if (record.getXrayImage() != null && record.getXrayImage().getUri() != null) {
            loadStoredXray(record);
        } else {
            xrayImageView.setImageDrawable(null);
            xrayBlackMask.setVisibility(View.VISIBLE);
        }

        String report = record.getAiReport();
        if (report == null || report.trim().isEmpty()) {
            xrayAiResultText.setText("该历史案例暂无AI分析结果。\n\n可在AI分析页面选择此案例进行分析。");
        } else {
            xrayAiResultText.setText(report.trim());
        }
    }

    private void loadStoredXray(CaseRepository.CaseRecord record) {
        Context appContext = requireContext().getApplicationContext();
        executor.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = ImageRepository.loadThumbnail(
                        appContext, record.getXrayImage().getUri(), 1600, 1400
                );
            } catch (Exception ignored) {
            }
            final Bitmap result = bitmap;
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (getView() == null) return;
                if (result != null) {
                    xrayImageView.setImageBitmap(result);
                    xrayBlackMask.setVisibility(View.GONE);
                } else {
                    xrayImageView.setImageDrawable(null);
                    xrayBlackMask.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private String formatTime(long timeMillis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date(timeMillis > 0L ? timeMillis : System.currentTimeMillis()));
    }

    @Override
    public void onDestroyView() {
        xrayImageView = null;
        xrayBlackMask = null;
        xrayCenterPanel = null;
        xrayCenterHintText = null;
        xrayStatusText = null;
        xrayCaptureTimeText = null;
        xrayAiResultText = null;
        xrayHistoryButton = null;
        xrayHistoryCornerButton = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
