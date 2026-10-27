package com.weiguang.drcontroller.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.weiguang.drcontroller.MainActivity;
import com.weiguang.drcontroller.R;
import com.weiguang.drcontroller.hardware.storage.CaseRepository;

public class AiReportFragment extends Fragment {
    private static final String ARG_CASE_ID = "case_id";

    public AiReportFragment() { super(R.layout.fragment_ai_report); }

    public static AiReportFragment newInstance(String caseId) {
        AiReportFragment fragment = new AiReportFragment();
        Bundle args = new Bundle();
        args.putString(ARG_CASE_ID, caseId);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        String caseId = getArguments() == null ? "" : getArguments().getString(ARG_CASE_ID, "");
        CaseRepository.CaseRecord record = CaseRepository.findCase(requireContext(), caseId);
        TextView name = view.findViewById(R.id.aiReportCaseName);
        TextView content = view.findViewById(R.id.aiReportContent);
        if (record == null) {
            name.setText("案例不存在");
            content.setText("无法读取该历史案例的AI分析报告。");
        } else {
            String label = record.getDisplayName();
            name.setText(label == null || label.trim().isEmpty() ? "历史案例" : label);
            content.setText(record.hasAiReport() ? record.getAiReport() : "该案例尚未生成AI分析报告。");
            if (record.hasAiReport()) CaseRepository.markAiReportRead(requireContext(), caseId);
        }
        Button back = view.findViewById(R.id.aiReportBackButton);
        back.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).openAiForCase(caseId);
            }
        });
    }
}
