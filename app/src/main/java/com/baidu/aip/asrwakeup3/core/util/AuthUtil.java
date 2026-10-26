package com.baidu.aip.asrwakeup3.core.util;

import com.baidu.asr.authlibrary.TemporaryToken;
import com.weiguang.drcontroller.BuildConfig;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;

/**
 * 为方便说明 apiKey 简称ak，secretKey简称 sk
 * ak sk为敏感信息，泄露后别人可使用ak sk 消耗你的调用次数，造成财产损失，请妥善保存
 * 建议你将ak sk保存在自己服务端，通过接口请求获得。
 * 如果暂时没有后端服务接口，建议将ak sk加密存储减少暴露风险。
 * 注意：本文件属于已弃用的离线唤醒 SDK，仅作兼容保留；凭据统一取 BuildConfig，
 * 维护位置：app/build.gradle.kts 的 buildConfigField。
 **/
public class AuthUtil {
    public static String getAk(){
        return BuildConfig.BAIDU_API_KEY;
    }
    public static String getSk(){
        return BuildConfig.BAIDU_SECRET_KEY;
    }
    public static String getAppId(){
        return BuildConfig.BAIDU_APP_ID;
    }
    public static String getIamKey(){
        // todo 填入iamKey
        return  "请填入iamKey";
    }
    public static TemporaryToken getToken(){

        // 获取token地址
        String authHost = "https://aip.baidubce.com/oauth/2.0/token?";
        String getAccessTokenUrl = authHost
                // 1. grant_type为固定参数
                + "grant_type=client_credentials"
                // 2. 官网获取的 API Key
                + "&client_id=" + getAk()
                // 3. 官网获取的 Secret Key
                + "&client_secret=" + getSk();
        try {
            URL realUrl = new URL(getAccessTokenUrl);
            // 打开和URL之间的连接
            HttpURLConnection connection = (HttpURLConnection) realUrl.openConnection();
            connection.setRequestMethod("GET");
            connection.connect();
            // 获取所有响应头字段
            Map<String, List<String>> map = connection.getHeaderFields();
            // 遍历所有的响应头字段
            for (String key : map.keySet()) {
                System.err.println(key + "--->" + map.get(key));
            }
            // 定义 BufferedReader输入流来读取URL的响应
            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            String result = "";
            String line;
            while ((line = in.readLine()) != null) {
                result += line;
            }
            /**
             * 返回结果示例
             */
            System.err.println("result:" + result);
            JSONObject jsonObject = new JSONObject(result);
            String accessToken = jsonObject.getString("access_token");
            long time = jsonObject.getLong("expires_in");
            return new TemporaryToken(accessToken ,
                    System.currentTimeMillis() + time);
        } catch (Exception e) {
            System.err.printf("获取token失败:" + e.getMessage());
            e.printStackTrace(System.err);
        }
        return null;
    }
}
