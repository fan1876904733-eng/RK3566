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

import com.weiguang.drcontroller.MainActivity;
import com.weiguang.drcontroller.R;
import com.weiguang.drcontroller.hardware.storage.CaseRepository;
import com.weiguang.drcontroller.hardware.storage.ImageRepository;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CaseDetailFragment extends Fragment {

    private static final String ARG_CASE_ID = "case_id";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private String caseId;
    private CaseRepository.CaseRecord currentCase;

    public CaseDetailFragment() {
        super(R.layout.fragment_case_detail);
    }

    public static CaseDetailFragment newInstance(String caseId) {
        CaseDetailFragment fragment = new CaseDetailFragment();
        Bundle args = new Bundle();
        args.putString(ARG_CASE_ID, caseId);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        caseId = getArguments() == null ? null : getArguments().getString(ARG_CASE_ID);

        Button backButton = view.findViewById(R.id.caseBackButton);
        Button deleteButton = view.findViewById(R.id.caseDeleteButton);
        backButton.setOnClickListener(v -> openHistory());
        deleteButton.setOnClickListener(v -> confirmDelete());
        loadCase();
    }

    private void loadCase() {
        if (caseId == null || caseId.trim().isEmpty()) {
            Toast.makeText(requireContext(), "案例不存在", Toast.LENGTH_SHORT).show();
            openHistory();
            return;
        }
        Context appContext = requireContext().getApplicationContext();
        executor.execute(() -> {
            CaseRepository.CaseRecord record = CaseRepository.findCase(appContext, caseId);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> renderCase(record));
        });
    }

    private void renderCase(CaseRepository.CaseRecord record) {
        if (!isAdded() || getView() == null) return;
        if (record == null) {
            Toast.makeText(requireContext(), "该案例已不存在", Toast.LENGTH_SHORT).show();
            openHistory();
            return;
        }
        currentCase = record;
        CaseRepository.markAiReportRead(requireContext().getApplicationContext(), caseId);
        View root = getView();
        TextView timeText = root.findViewById(R.id.caseTimeText);
        TextView radarText = root.findViewById(R.id.caseRadarText);
        TextView reportText = root.findViewById(R.id.caseAiReportText);
        String name = record.getDisplayName();
        timeText.setText(name == null || name.trim().isEmpty()
                ? "拍摄时间：" + formatTime(record.getTimeMillis())
                : "案例：" + name + " · 拍摄于 " + formatTime(record.getTimeMillis()));

        CaseRepository.RadarSnapshot radar = record.getRadarSnapshot();
        if (radar == null) {
            radarText.setText("暂无毫米波雷达数据");
        } else {
            radarText.setText(
                    "连接状态：" + (radar.isConnected() ? "连接成功" : "待连接") +
                    "\n物体状态：" + radar.getTargetStatus() +
                    "\n距离：" + radar.getDistance() +
                    "\n呼吸：" + radar.getBreathRate() +
                    "\n心率：" + radar.getHeartRate()
            );
        }
        reportText.setText(record.hasAiReport() ? record.getAiReport() : "尚未进行AI分析");

        loadImage(record.getCameraImage(), R.id.caseCameraImage, R.id.caseCameraEmptyText);
        loadImage(record.getThermalImage(), R.id.caseThermalImage, R.id.caseThermalEmptyText);
        loadXrayImage(record);
    }

    private void loadXrayImage(CaseRepository.CaseRecord record) {
        if (getView() == null || record == null) return;
        ImageView imageView = getView().findViewById(R.id.caseXrayImage);
        TextView emptyView = getView().findViewById(R.id.caseXrayEmptyText);

        if (record.getXrayImage() != null && record.getXrayImage().getUri() != null) {
            loadImage(record.getXrayImage(), R.id.caseXrayImage, R.id.caseXrayEmptyText);
            return;
        }

        imageView.setImageDrawable(null);
        emptyView.setText("暂无X-Ray图");
        emptyView.setVisibility(View.VISIBLE);
    }

    private void loadImage(
            ImageRepository.ImageRecord image,
            int imageViewId,
            int emptyViewId
    ) {
        if (getView() == null) return;
        ImageView imageView = getView().findViewById(imageViewId);
        TextView emptyView = getView().findViewById(emptyViewId);
        imageView.setImageDrawable(null);
        emptyView.setVisibility(View.VISIBLE);
        if (image == null || image.getUri() == null) return;

        Context appContext = requireContext().getApplicationContext();
        executor.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = ImageRepository.loadThumbnail(appContext, image.getUri(), 1280, 900);
            } catch (Exception ignored) {
            }
            final Bitmap result = bitmap;
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (getView() == null) return;
                if (result != null) {
                    imageView.setImageBitmap(result);
                    emptyView.setVisibility(View.GONE);
                } else {
                    emptyView.setText("影像读取失败");
                    emptyView.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private void confirmDelete() {
        if (currentCase == null) return;
        new AlertDialog.Builder(requireContext())
                .setTitle("删除案例")
                .setMessage("将删除该案例的实时图、热成像图、X-Ray图、毫米波数据和AI报告。是否继续？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) ->
                        CaseRepository.deleteCase(requireContext(), currentCase,
                                new CaseRepository.DeleteCallback() {
                                    @Override
                                    public void onDeleted() {
                                        if (!isAdded()) return;
                                        Toast.makeText(requireContext(), "案例已删除", Toast.LENGTH_SHORT).show();
                                        openHistory();
                                    }

                                    @Override
                                    public void onError(String message) {
                                        if (!isAdded()) return;
                                        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                                    }
                                }))
                .show();
    }

    private void openHistory() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).backToHistoryFromDetail();
        }
    }

    private String formatTime(long timeMillis) {
        // 固定按北京时间显示，避免设备时区设置异常导致时间错误。
        SimpleDateFormat format = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.CHINA
        );
        format.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        return format.format(new Date(timeMillis > 0L ? timeMillis : System.currentTimeMillis()));
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
