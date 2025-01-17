package com.xiaomi.miapi.service.impl;

import com.alibaba.fastjson.JSONArray;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.xiaomi.miapi.common.bo.NacosInfo;
import com.xiaomi.miapi.common.dto.ProjectApisDTO;
import com.xiaomi.miapi.common.pojo.*;
import com.xiaomi.miapi.mapper.*;
import com.xiaomi.miapi.util.HttpUtils;
import com.xiaomi.miapi.util.RedisUtil;
import com.xiaomi.miapi.common.Consts;
import com.xiaomi.miapi.common.Result;
import com.xiaomi.miapi.common.bo.NacosLoginInfo;
import com.xiaomi.data.push.nacos.NacosNaming;
import com.xiaomi.miapi.dto.HttpJsonParamBo;
import com.xiaomi.mone.dubbo.docs.core.beans.LayerItem;
import com.xiaomi.miapi.service.ApiService;
import com.xiaomi.miapi.service.MockService;
import com.xiaomi.miapi.common.exception.CommonError;
import com.xiaomi.youpin.codegen.HttpRequestGen;
import com.xiaomi.youpin.codegen.bo.ApiHeaderBo;
import com.xiaomi.youpin.codegen.bo.HttpJsonParamsBo;
import com.xiaomi.youpin.hermes.service.BusProjectService;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.annotation.DubboReference;
import org.checkerframework.checker.units.qual.A;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.lang.reflect.InvocationTargetException;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 接口业务处理层
 */
@Service
public class ApiServiceImpl implements ApiService {

    @Autowired
    RedisUtil redis;

    @Autowired
    ApiMapper apiMapper;

    @Autowired
    EoDubboApiInfoMapper dubboApiInfoMapper;

    @Autowired
    GatewayApiInfoMapper gatewayApiInfoMapper;

    @Autowired
    ApiGroupMapper apiGroupMapper;

    @Autowired
    ApiRequestExpMapper requestExpMapper;

    @Autowired
    ApiResponseExpMapper responseExpMapper;

    @Autowired
    RecordService recordService;

    @Autowired
    ApiIndexMapper apiIndexMapper;

    @Autowired
    IndexInfoMapper indexInfoMapper;

    @Autowired
    ApiHistoryRecordMapper historyRecordMapper;

    @Autowired
    private ApiRequestExpMapper apiRequestExpMapper;

    @Autowired
    private ApiResponseExpMapper apiResponseExpMapper;

    @Autowired
    MockService mockService;

    @Autowired
    NacosInfo nacosInfo;

    @DubboReference(check = false, group = "${ref.hermes.service.group}")
    private BusProjectService busProjectService;

    @Resource(name = "nacosNamingSt")
    private NacosNaming nacosNamingSt;

    @Resource(name = "nacosNamingOl")
    private NacosNaming nacosNamingOl;

    public static final Gson gson = new Gson();

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiServiceImpl.class);

    public static String stNacosAccessToken = "";

    public static String olNacosAccessToken = "";

    private static final HttpRequestGen codeGenerator = new HttpRequestGen();

    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();

    /**
     * 每5分钟更新nacos的accessToken
     */
    @PostConstruct
    public void init() {
        executorService.scheduleAtFixedRate(this::refreshStNacosToken, 0, 5, TimeUnit.MINUTES);

        executorService.scheduleAtFixedRate(this::refreshOlNacosToken, 0, 5, TimeUnit.MINUTES);

    }

    public void refreshStNacosToken() {
        //测试环境nacos
        try {
            NacosLoginInfo stNacosLoginInfo = new Gson().fromJson(nacosNamingSt.login(nacosInfo.getUsernameSt(), nacosInfo.getPasswordSt()), NacosLoginInfo.class);
            if (null != stNacosLoginInfo && StringUtils.isNotEmpty(stNacosLoginInfo.getAccessToken())) {
                stNacosAccessToken = stNacosLoginInfo.getAccessToken();
            }
        } catch (Throwable ex) {
            LOGGER.error(ex.getMessage());
        }
    }

    private void refreshOlNacosToken() {
        //线上环境nacos
        try {
            NacosLoginInfo olNacosLoginInfo = new Gson().fromJson(nacosNamingOl.login(nacosInfo.getUsernameOl(), nacosInfo.getPasswordOl()), NacosLoginInfo.class);
            if (null != olNacosLoginInfo && StringUtils.isNotEmpty(olNacosLoginInfo.getAccessToken())) {
                olNacosAccessToken = olNacosLoginInfo.getAccessToken();
            }
        } catch (Throwable ex) {
            LOGGER.error(ex.getMessage());
        }
    }

    @Override
    public Result<Boolean> editApiStatus(Integer projectId, Integer apiId, Integer status) {
        Api api = apiMapper.getApiInfo(projectId, apiId);
        if (api == null) {
            return Result.fail(CommonError.APIDoNotExist);
        }
        api.setApiStatus(status);
        if (apiMapper.updateApi(api) > 0) {
            recordService.doRecord(api, null, "[快速保存]修改接口状态", "[快速保存]修改接口状态:" + api.getApiName(), ProjectOperationLog.OP_TYPE_UPDATE);
            return Result.success(true);
        }
        return Result.fail(CommonError.UnknownError);
    }

    @Override
    public Result<Boolean> editApiDiyExp(Integer apiID, Integer expType, Integer type,String content) {
        if (type == 1){
            //修改请求示例
            ApiRequestExpExample example = new ApiRequestExpExample();
            example.createCriteria().andApiIdEqualTo(apiID).andRequestParamExpTypeEqualTo(expType);
            List<ApiRequestExp> apiRequestExpList = apiRequestExpMapper.selectByExample(example);
            if (apiRequestExpList.isEmpty()){
                //新增
                ApiRequestExp exp = new ApiRequestExp();
                exp.setApiId(apiID);
                exp.setRequestParamExpType(expType);
                exp.setCodeGenExp(content);
                apiRequestExpMapper.insert(exp);
            }else {
                //存在即更新
                ApiRequestExp exp = apiRequestExpList.get(0);
                exp.setCodeGenExp(content);
                apiRequestExpMapper.updateByPrimaryKeyWithBLOBs(exp);
            }
        }else {
            //修改返回示例
            ApiResponseExpExample example = new ApiResponseExpExample();
            example.createCriteria().andApiIdEqualTo(apiID).andRespGenExpTypeEqualTo(expType);
            List<ApiResponseExp> expList = apiResponseExpMapper.selectByExample(example);
            if (expList.isEmpty()){
                //新增
                ApiResponseExp exp = new ApiResponseExp();
                exp.setApiId(apiID);
                exp.setRespGenExpType(expType);
                exp.setRespGenExp(content);
                apiResponseExpMapper.insert(exp);
            }else {
                //存在即更新
                ApiResponseExp exp = expList.get(0);
                exp.setRespGenExp(content);
                apiResponseExpMapper.updateByPrimaryKeyWithBLOBs(exp);
            }
        }
        return Result.success(true);
    }

    /**
     * 移除接口到回收站
     */
    @Override
    public boolean removeApi(Integer projectID, String apiID, Integer userID, String username) {
        Integer result = 0;
        Date date = new Date();
        Timestamp updateTime = new Timestamp(date.getTime());
        JSONArray jsonArray = JSONArray.parseArray(apiID);
        List<Integer> apiIDs = new ArrayList<Integer>();
        if (jsonArray != null && !jsonArray.isEmpty()) {
            for (Iterator<Object> iterator = jsonArray.iterator(); iterator.hasNext(); ) {
                apiIDs.add((Integer) iterator.next());
            }
            result = apiMapper.removeApi(projectID, apiIDs, updateTime);
        }
        if (result > 0) {
            String apiName = apiMapper.getApiNameByIDs(apiIDs);
            Api api = new Api();
            api.setProjectID(projectID);
            api.setApiUpdateTime(updateTime);
            api.setUpdateUsername(username);
            recordService.doRecord(api, null, "将接口:'" + apiName + "'移入接口回收站", "将接口:'" + apiName + "'移入接口回收站", ProjectOperationLog.OP_TYPE_DELETE);
            return true;
        }
        return false;
    }

    /**
     * 删除接口
     */
    @Override
    @Transactional
    public boolean deleteApi(Integer projectID, String apiID, String username) {
        String apiName = "";
        Integer result = 0;
        JSONArray jsonArray = JSONArray.parseArray(apiID);
        List<Integer> apiIDs = new ArrayList<Integer>();
        List<Api> apis = new ArrayList<>();
        if (jsonArray != null && !jsonArray.isEmpty()) {
            for (Iterator<Object> iterator = jsonArray.iterator(); iterator.hasNext(); ) {
                apiIDs.add((Integer) iterator.next());
            }
            apiName = apiMapper.getApiNameByIDs(apiIDs);
            apis = apiMapper.getApiListByIDs(apiIDs);

            result = apiMapper.deleteApi(projectID, apiIDs);
        }

        List<Integer> dubboApiIds = new ArrayList<>();
        List<Integer> gatewayApiIds = new ArrayList<>();

        for (Api api :
                apis) {
            if (api.getApiProtocol() == Consts.DUBBO_API_TYPE) {
                dubboApiIds.add(api.getDubboApiId());
            } else if (api.getApiProtocol() == Consts.GATEWAY_API_TYPE) {
                gatewayApiIds.add(api.getGatewayApiId());
            }
        }
        Date date = new Date();
        Timestamp updateTime = new Timestamp(date.getTime());
        if (result > 0) {
            //删除dubbo类型api数据及参数
            if (dubboApiIds.size() != 0) {
                dubboApiInfoMapper.batchDeleteDubboApi(dubboApiIds);
            }
            //删除网关类型api数据
            if (gatewayApiIds.size() != 0) {
                gatewayApiInfoMapper.batchDeleteGatewayApi(gatewayApiIds);
            }

            Api api = new Api();
            api.setProjectID(projectID);
            api.setUpdateUsername(username);
            api.setApiUpdateTime(updateTime);
            recordService.doRecord(api, null, null, "彻底删除接口：'" + apiName + "'", ProjectOperationLog.OP_TYPE_DELETE);
            return true;
        }
        return false;
    }


    /**
     * 获取接口列表
     */
    @Override
    public Map<String, Object> getApiList(Integer pageNo, Integer pageSize, Integer projectID, Integer groupID, Integer orderBy, Integer asc) {
        if (pageNo <= 0) {
            pageNo = 1;
        }
        if (pageSize <= 0) {
            pageSize = Consts.DEFAULT_PAGE_SIZE;
        }
        int offset = (pageNo - 1) * pageSize;

        String order = "";
        if (orderBy == null) {
            orderBy = 3;
        }
        switch (orderBy) {
            case 0:
                order = "eo_api.apiName";
                break;
            case 1:
                order = "eo_api.apiUpdateTime";
                break;
            case 2:
                order = "eo_api.starred";
                break;
            case 3:
                order = "eo_api.apiID";
                break;
        }
        if (asc == null) {
            asc = 0;
        }
        String by = asc == 0 ? "ASC" : "DESC";
        List<Integer> groupIDS = new ArrayList<Integer>();
        if (groupID == 0) {
            List<Map<String, Object>> groupList = apiGroupMapper.getGroupList(projectID);
            for (Map<String, Object> group :
                    groupList) {
                groupIDS.add(((Long) group.get("groupID")).intValue());
            }
        } else {
            groupIDS.add(groupID);
        }
        List<Map<String, Object>> apiList;
        if (groupID == 0) {
            apiList = apiMapper.getAllApiList(projectID, order + " " + by, offset, pageSize);
        } else {
            apiList = apiMapper.getApiList(projectID, groupIDS, order + " " + by, offset, pageSize);
        }
        apiList.forEach(api -> {
            if (StringUtils.isEmpty(String.valueOf(api.get("apiEnv")))) {
                api.put("apiEnv", "staging");
            }
        });
        Map<String, Object> map = new HashMap<>();
        map.put("apiList", apiList);
        map.put("apiCount", apiMapper.getApiListNum(projectID, groupIDS));
        return map;
    }

    @Override
    public Result<List<Map<String, Object>>> getApiListByProjectId(ProjectApisDTO dto) {
        if (dto.getPageNo() <= 0) {
            dto.setPageNo(1);
        }
        if (dto.getPageSize() <= 0) {
            dto.setPageSize(Consts.DEFAULT_PAGE_SIZE);
        }
        int offset = (dto.getPageNo() - 1) * dto.getPageSize();
        List<Integer> groupIDS = new ArrayList<Integer>();

        List<Map<String, Object>> groupList = apiGroupMapper.getGroupList(dto.getProjectID());
        for (Map<String, Object> group :
                groupList) {
            groupIDS.add(((Long) group.get("groupID")).intValue());
        }
        List<Map<String, Object>> apiList = apiMapper.getApiByProjectId(dto.getProjectID(), groupIDS, "eo_api_cache.apiID ASC", offset, dto.getPageSize());
        return Result.success(apiList);
    }

    @Override
    public Map<Integer, List<Map<String, Object>>> getGroupApiViewList(Integer projectID) {

        Map<Integer, List<Map<String, Object>>> map = new HashMap<>();
        List<Map<String, Object>> list = apiMapper.getGroupApiViewList(projectID);
        for (Map<String, Object> api :
                list) {
            if (map.containsKey(((Long) api.get("groupID")).intValue())) {
                map.get(((Long) api.get("groupID")).intValue()).add(api);
            } else {
                List<Map<String, Object>> l = new ArrayList<>();
                l.add(api);
                map.put(((Long) api.get("groupID")).intValue(), l);
            }
        }
        return map;
    }

    @Override
    public Map<Integer, List<Map<String, Object>>> getAllIndexGroupApiViewList(Integer projectID) {
        Map<Integer, List<Map<String, Object>>> map = new HashMap<>();

        IndexInfoExample example = new IndexInfoExample();
        example.createCriteria().andProjectIdEqualTo(projectID);
        List<IndexInfo> indices = indexInfoMapper.selectByExample(example);
        if (indices == null || indices.size() == 0) {
            return map;
        }
        List<Integer> indexIds = new ArrayList<>(indices.size());
        for (IndexInfo index :
                indices) {
            indexIds.add(index.getIndexId());
        }
        List<Map<String, Object>> list = apiMapper.getAllIndexGroupApiViewListByIndices(indexIds);

        for (Map<String, Object> api : list
        ) {
            api.put("isIndex", true);
            if (map.containsKey((api.get("index_id")))) {
                map.get((api.get("index_id"))).add(api);
            } else {
                List<Map<String, Object>> l = new ArrayList<>();
                l.add(api);
                map.put((Integer) api.get("index_id"), l);
            }
        }
        return map;
    }

    @Override
    public Result<List<Map<String, String>>> getApiListByIndex(Integer indexID) throws IllegalAccessException, NoSuchMethodException, InvocationTargetException {

        List<Map<String, String>> result = new ArrayList<>();

        ApiIndexExample example = new ApiIndexExample();
        example.createCriteria().andIndexIdEqualTo(indexID);
        List<ApiIndex> apiIndices = apiIndexMapper.selectByExample(example);

        if (apiIndices == null || apiIndices.size() == 0) {
            return Result.success(result);
        }
        List<Integer> apiIds = new ArrayList<>();
        for (ApiIndex apiIndex :
                apiIndices) {
            apiIds.add(apiIndex.getApiId());
        }
        List<Api> apiList = apiMapper.getApiListByIDs(apiIds);

        for (Api api :
                apiList) {
            Map<String, String> map = org.apache.commons.beanutils.BeanUtils.describe(api);
            map.put("isIndex", "true");
            result.add(map);
        }
        return Result.success(result);
    }

    public List<Api> getRecentlyApiList(Integer userId) {
        List<Api> apiList = new ArrayList<>();
        List<String> apiIdsStr = redis.lRange(Consts.genRecentlyApisKey(userId), 0, 8);
        if (apiIdsStr == null || apiIdsStr.size() == 0) {
            return apiList;
        }
        List<Integer> apiIds = new ArrayList<>();
        for (String id :
                apiIdsStr) {
            apiIds.add(Integer.parseInt(id));
        }
        return apiMapper.getApiListByIDs(apiIds);
    }

    /**
     * 搜索接口
     */
    @Override
    public List<Map<String, Object>> searchApi(Integer projectID, String tips, Integer type) {
        if (type == Consts.BY_API_NAME) {
            return apiMapper.searchApiByName(projectID, tips);
        } else if (type == Consts.BY_API_PATH) {
            return apiMapper.searchApiByPath(projectID, tips);
        }
        //both
        return apiMapper.searchApi(projectID, tips);
    }


    /**
     * 获取接口历史列表
     */
    @Override
    public List<Map<String, Object>> getApiHistoryList(Integer projectID, Integer apiID) {
        return null;
    }


    public Api checkAndFillApiInfo(Api api) {
        if (StringUtils.isEmpty(api.getApiEnv())) {
            api.setApiEnv("staging");
        }
        if (api.getApiRequestRaw() == null) {
            api.setApiRequestRaw("");
        }
        if (api.getApiDesc() == null || api.getApiDesc().equals("&lt;p&gt;&lt;br&gt;&lt;/p&gt;")) {
            api.setApiDesc("");
        }
        if (api.getApiRemark() == null) {
            api.setApiRemark("");
        }
        if (api.getStarred() == null) {
            api.setStarred(0);
        }
        if (api.getApiNoteType() == null) {
            api.setApiNoteType(0);
        }
        if (api.getApiRequestParamType() == null) {
            api.setApiRequestParamType(0);
        }
        if (api.getApiStatus() == null) {
            api.setApiStatus(0);
        }
        api.setApiUpdateTime(new Timestamp(System.currentTimeMillis()));
        api.setRemoved(0);

        return api;
    }

    @Transactional
    public void recordApiHistory(Api api, String apiJson, String updateMsg) {
        ApiHistoryRecordExample example = new ApiHistoryRecordExample();
        example.createCriteria().andApiIdEqualTo(api.getApiID()).andIsNowEqualTo(true);
        //变更当前api标志
        List<ApiHistoryRecord> records = historyRecordMapper.selectByExampleWithBLOBs(example);
        ApiHistoryRecord old = null;
        if (!records.isEmpty()) {
            old = records.get(0);
            old.setIsNow(false);
            historyRecordMapper.updateByPrimaryKey(old);
        }

        ApiHistoryRecord historyRecord = new ApiHistoryRecord();
        historyRecord.setApiId(api.getApiID());
        historyRecord.setApiHistiryJson(apiJson);
        historyRecord.setProjectId(api.getProjectID());
        historyRecord.setGroupId(api.getGroupID());
        historyRecord.setUpdateUser(api.getUpdateUsername());
        historyRecord.setUpdateTime(api.getApiUpdateTime());
        historyRecord.setApiProtocal(api.getApiProtocol());
        historyRecord.setUpdateMsg(updateMsg);
        historyRecord.setIsNow(true);
        if (historyRecordMapper.insert(historyRecord) <= 0) {
            LOGGER.warn("historyRecordMapper.insert history error,apiMsg:{}", historyRecord.getApiHistiryJson());
        }
        //通知测试团队
        if (!updateMsg.equals(Consts.IMPORT_SWAGGER_FLAG)) {
            ApiHistoryRecord finalOld = old;
            new Thread(() -> notifyTestTeam(historyRecord, finalOld)).start();
        }
    }

    public void dubboApiCodeGen(Api api, EoDubboApiInfo dubboApiInfo, String responseExp, String reqExp, boolean isUpdate) {
        List<ApiRequestExp> requestExpList = new ArrayList<>();
        //请求示例代码生成
        ApiRequestExp dubboJavaExp = new ApiRequestExp();
        ApiRequestExp dubboRawExp = new ApiRequestExp();
        requestExpList.add(dubboJavaExp);
        requestExpList.add(dubboRawExp);

        dubboJavaExp.setApiId(api.getApiID());
        dubboRawExp.setApiId(api.getApiID());

        String dubboJavaExpCode = "";
        String[] modelArr = dubboApiInfo.getApimodelclass().split("\\.");

        com.xiaomi.youpin.infra.rpc.Result<String> dubboJavaGenResult = codeGenerator.generateDubboJavaReq(modelArr[modelArr.length - 1], dubboApiInfo.getApiname(), dubboApiInfo.getApigroup(), dubboApiInfo.getApiversion());
        if (Objects.nonNull(dubboJavaGenResult) && dubboJavaGenResult.getMessage().equals("ok")) {
            dubboJavaExpCode = dubboJavaGenResult.getData();
        }
        dubboJavaExp.setCodeGenExp(dubboJavaExpCode);
        dubboRawExp.setCodeGenExp(reqExp);
        dubboJavaExp.setRequestParamExpType(Consts.REQ_EXP_JAVA_TYPE);
        dubboRawExp.setRequestParamExpType(Consts.REQ_EXP_RAW_TYPE);

        //返回示例
        ApiResponseExp dubboRspExp = new ApiResponseExp();
        dubboRspExp.setApiId(api.getApiID());
        dubboRspExp.setRespGenExp(responseExp);
        dubboRspExp.setRespGenExpType(Consts.RSP_EXP_JSON_TYPE);

        if (!isUpdate) {
            requestExpMapper.batchInsert(requestExpList);
            responseExpMapper.insert(dubboRspExp);
        } else {
            //更新 response
            ApiResponseExpExample example = new ApiResponseExpExample();
            example.createCriteria().andApiIdEqualTo(api.getApiID()).andRespGenExpTypeEqualTo(Consts.RSP_EXP_JSON_TYPE);
            List<ApiResponseExp> expList = responseExpMapper.selectByExample(example);
            if (Objects.nonNull(expList) && !expList.isEmpty()) {
                dubboRspExp.setId(expList.get(0).getId());
                responseExpMapper.updateByExampleWithBLOBs(dubboRspExp, example);
            }
            //更新 req java
            ApiRequestExpExample example2 = new ApiRequestExpExample();
            example2.createCriteria().andApiIdEqualTo(api.getApiID()).andRequestParamExpTypeEqualTo(Consts.REQ_EXP_JAVA_TYPE);
            List<ApiRequestExp> exp2List = requestExpMapper.selectByExample(example2);
            if (Objects.nonNull(exp2List) && !exp2List.isEmpty()) {
                dubboJavaExp.setId(exp2List.get(0).getId());
                requestExpMapper.updateByExampleWithBLOBs(dubboJavaExp, example2);
            }
            //更新 req raw
            ApiRequestExpExample example3 = new ApiRequestExpExample();
            example3.createCriteria().andApiIdEqualTo(api.getApiID()).andRequestParamExpTypeEqualTo(Consts.REQ_EXP_RAW_TYPE);
            List<ApiRequestExp> exp3List = requestExpMapper.selectByExample(example3);
            if (Objects.nonNull(exp3List) && !exp3List.isEmpty()) {
                dubboRawExp.setId(exp3List.get(0).getId());
                requestExpMapper.updateByExampleWithBLOBs(dubboRawExp, example3);
            }
        }
    }

    public void codeGen(Api api, int apiRequestParamType, String apiRequestParam, int apiResultParamType, String apiResultParam, List<ApiHeaderBo> headers, boolean isUpdate) {
        //请求示例代码生成
        String methodName = api.getApiURI().substring(api.getApiURI().lastIndexOf('/') + 1);
        ApiRequestExp requestJavaExp = new ApiRequestExp();
        requestJavaExp.setApiId(api.getApiID());
        ApiRequestExp requestCurlExp = new ApiRequestExp();
        requestCurlExp.setApiId(api.getApiID());

        String requestJavaExpCode = "";
        String requestCurlExpCode = "";
        //表单类型参数生成java/curl代码
        if (apiRequestParamType == Consts.FORM_DATA_TYPE) {
            com.xiaomi.youpin.infra.rpc.Result<String> javaGenResult = codeGenerator.generateJavaReq(methodName, Consts.FORM_DATA_TYPE, 0, apiRequestParam);
            if (Objects.nonNull(javaGenResult) && javaGenResult.getMessage().equals("ok")) {
                requestJavaExpCode = javaGenResult.getData();
            }
            com.xiaomi.youpin.infra.rpc.Result<String> curlGenResult = codeGenerator.generateCurlReq(api.getApiRequestType(), api.getApiURI(), apiRequestParamType, apiRequestParam, headers);
            if (Objects.nonNull(curlGenResult) && curlGenResult.getMessage().equals("ok")) {
                requestCurlExpCode = curlGenResult.getData();
            }
        } else if (apiRequestParamType == Consts.JSON_DATA_TYPE) {
            //json类型参数生成java/curl代码
            List<HttpJsonParamsBo> paramList = gson.fromJson(apiRequestParam, new TypeToken<List<HttpJsonParamsBo>>() {
            }.getType());
            if (!paramList.isEmpty()) {
                String paramsJson = gson.toJson(paramList.get(0).getChildList());
                com.xiaomi.youpin.infra.rpc.Result<String> javaGenResult = codeGenerator.generateJavaReq(methodName, Consts.JSON_DATA_TYPE, paramList.get(0).getParamType(), paramsJson);
                if (Objects.nonNull(javaGenResult) && javaGenResult.getMessage().equals("ok")) {
                    requestJavaExpCode = javaGenResult.getData();
                }
                com.xiaomi.youpin.infra.rpc.Result<String> curlGenResult = codeGenerator.generateCurlReq(api.getApiRequestType(), api.getApiURI(), apiRequestParamType, gson.toJson(mockService.parseStructToJson(gson.toJson(paramList),false)), headers);
                if (Objects.nonNull(curlGenResult) && curlGenResult.getMessage().equals("ok")) {
                    requestCurlExpCode = curlGenResult.getData();
                }
            }
        } else {
            com.xiaomi.youpin.infra.rpc.Result<String> javaGenResult = codeGenerator.generateJavaReq(methodName, Consts.RAW_DATA_TYPE, 0, "");
            if (Objects.nonNull(javaGenResult) && javaGenResult.getMessage().equals("ok")) {
                requestJavaExpCode = javaGenResult.getData();
            }
            com.xiaomi.youpin.infra.rpc.Result<String> curlGenResult = codeGenerator.generateCurlReq(api.getApiRequestType(), api.getApiURI(), apiRequestParamType, "", headers);
            if (Objects.nonNull(curlGenResult) && curlGenResult.getMessage().equals("ok")) {
                requestCurlExpCode = curlGenResult.getData();
            }
        }
        requestJavaExp.setRequestParamExpType(Consts.REQ_EXP_JAVA_TYPE);
        requestJavaExp.setCodeGenExp(requestJavaExpCode);

        requestCurlExp.setRequestParamExpType(Consts.REQ_EXP_CURL_TYPE);
        requestCurlExp.setCodeGenExp(requestCurlExpCode);
        //新增
        if (!isUpdate) {
            List<ApiRequestExp> expList = new ArrayList<>(2);
            expList.add(requestJavaExp);
            expList.add(requestCurlExp);
            requestExpMapper.batchInsert(expList);
        } else {
            //更新
            ApiRequestExpExample javaExample = new ApiRequestExpExample();
            javaExample.createCriteria().andApiIdEqualTo(api.getApiID()).andRequestParamExpTypeEqualTo(Consts.REQ_EXP_JAVA_TYPE);
            List<ApiRequestExp> oldList = requestExpMapper.selectByExample(javaExample);
            if (Objects.nonNull(oldList) && !oldList.isEmpty()) {
                //存在，则更新
                requestJavaExp.setId(oldList.get(0).getId());
                requestExpMapper.updateByExampleWithBLOBs(requestJavaExp, javaExample);
            } else {
                //否则新增
                requestExpMapper.insert(requestJavaExp);
            }

            ApiRequestExpExample curlExample = new ApiRequestExpExample();
            curlExample.createCriteria().andApiIdEqualTo(api.getApiID()).andRequestParamExpTypeEqualTo(Consts.REQ_EXP_CURL_TYPE);
            List<ApiRequestExp> oldList2 = requestExpMapper.selectByExample(curlExample);
            if (Objects.nonNull(oldList2) && !oldList2.isEmpty()) {
                //存在，则更新
                requestCurlExp.setId(oldList2.get(0).getId());
                requestExpMapper.updateByExampleWithBLOBs(requestCurlExp, curlExample);
            } else {
                //否则新增
                requestExpMapper.insert(requestCurlExp);
            }
        }

        //返回示例代码生成
        ApiResponseExp responseExp = new ApiResponseExp();
        responseExp.setApiId(api.getApiID());
        responseExp.setRespGenExpType(Consts.RSP_EXP_JSON_TYPE);
        if (apiResultParamType == Consts.JSON_DATA_TYPE) {
            Object respExpJson = mockService.parseStructToJson(apiResultParam,false);
            responseExp.setRespGenExp(gson.toJson(respExpJson));
        } else if (apiResultParamType == Consts.RAW_DATA_TYPE) {
            responseExp.setRespGenExp(api.getApiResponseRaw());
        }
        if (!isUpdate) {
            //新增
            responseExpMapper.insert(responseExp);
        } else {
            ApiResponseExpExample jsonExample = new ApiResponseExpExample();
            jsonExample.createCriteria().andApiIdEqualTo(api.getApiID()).andRespGenExpTypeEqualTo(Consts.RSP_EXP_JSON_TYPE);

            List<ApiResponseExp> oldList = responseExpMapper.selectByExample(jsonExample);
            if (Objects.nonNull(oldList) && !oldList.isEmpty()) {
                //已存在，更新
                responseExp.setId(oldList.get(0).getId());
                responseExpMapper.updateByExampleWithBLOBs(responseExp, jsonExample);
            } else {
                //不存在，插入
                responseExpMapper.insert(responseExp);
            }
        }
    }


    /**
     * 通知测试团队接口变更
     *
     * @param currentRecord 当前最新的接口信息
     * @param oldRecord     上一版接口信息
     */
    public void notifyTestTeam(ApiHistoryRecord currentRecord, ApiHistoryRecord oldRecord) {
        Map<String, String> body = new HashMap<>();
        compareApiAlterType(currentRecord, oldRecord, body);
        Map<String, String> header = new HashMap<>();

        body.put("projectName", busProjectService.queryBusProjectById(currentRecord.getProjectId()).getName());
        body.put("groupName", apiGroupMapper.getGroupByID(currentRecord.getGroupId()).getGroupName());
        body.put("currentRecord", gson.toJson(currentRecord));
        body.put("oldRecord", gson.toJson(oldRecord));
        header.put("Content-Type", "application/json;charset=UTF-8");
        try {
            if (body.get("isLogicUpdate").equals("0")) {
                String TEST_GROUP_NOTIFY_URL = "https://127.0.0.1/paris/openapi/apirecord";
                HttpUtils.post(TEST_GROUP_NOTIFY_URL, header, gson.toJson(body), 30000);
            }
        } catch (Exception ignored) {
        }
    }

    public void compareApiAlterType(ApiHistoryRecord currentRecord, ApiHistoryRecord oldRecord, Map<String, String> body) {
        String currentApiJson = currentRecord.getApiHistiryJson();
        if (Objects.isNull(oldRecord)) {
            body.put("isNew", "1");
            return;
        } else {
            body.put("isNew", "0");
        }
        String oldApiJson = oldRecord.getApiHistiryJson();
        Map<String, Object> currentMap = gson.fromJson(currentApiJson, new TypeToken<Map<String, Object>>() {
        }.getType());
        Map<String, Object> oldMap = gson.fromJson(oldApiJson, new TypeToken<Map<String, Object>>() {
        }.getType());

        //http/gateway类型
        if (currentRecord.getApiProtocal() == Consts.HTTP_API_TYPE || currentRecord.getApiProtocal() == Consts.GATEWAY_API_TYPE) {
            //比较入参
            if (!gson.toJson(currentMap.get("requestInfo")).equals(gson.toJson(oldMap.get("requestInfo")))) {
                body.put("inputParamChange", "1");
            } else {
                body.put("inputParamChange", "0");
            }
            //比较出参
            if (!gson.toJson(currentMap.get("resultInfo")).equals(gson.toJson(oldMap.get("resultInfo")))) {
                body.put("outputParamChange", "1");
            } else {
                body.put("outputParamChange", "0");
            }
        }

        //dubbo类型
        if (currentRecord.getApiProtocal() == Consts.DUBBO_API_TYPE) {
            Map<String, Object> cDubboInfo = (Map<String, Object>) currentMap.get("dubboInfo");
            Map<String, Object> oDubboInfo = (Map<String, Object>) oldMap.get("dubboInfo");
            if (Objects.nonNull(cDubboInfo)) {
                //比较入参
                if (!gson.toJson(cDubboInfo.get("methodparaminfo")).equals(gson.toJson(oDubboInfo.get("methodparaminfo")))) {
                    body.put("inputParamChange", "1");
                } else {
                    body.put("inputParamChange", "0");
                }
                //比较出参
                if (!gson.toJson(cDubboInfo.get("resultInfo")).equals(gson.toJson(oDubboInfo.get("resultInfo")))) {
                    body.put("outputParamChange", "1");
                } else {
                    body.put("outputParamChange", "0");
                }
            }
        }
        //若出入参都没有变动则判断为代码逻辑更新
        if (body.get("inputParamChange").equals("0") && body.get("outputParamChange").equals("0")) {
            body.put("isLogicUpdate", "1");
        } else {
            body.put("isLogicUpdate", "0");
        }
    }

    public static Integer transferStrMethod2Num(String methodName) {
        int num = 0;
        switch (methodName) {
            case "POST":
                break;
            case "GET":
                num = 1;
                break;
            case "PUT":
                num = 2;
                break;
            case "DELETE":
                num = 3;
                break;
            case "HEAD":
                num = 4;
                break;
            case "OPTS":
                num = 5;
                break;
            case "PATCH":
                num = 6;
                break;
            default:
        }
        return num;
    }

    public String transDubboParam2Http(String dubboParams) {
        List<HttpJsonParamBo> paramBos = new ArrayList<>();
        HttpJsonParamBo rootArr = new HttpJsonParamBo();
        rootArr.setParamKey("rootArr");
        //多类型数据数组
        rootArr.setParamType("12");
        rootArr.setParamNotNull(true);
        List<HttpJsonParamBo> paramList = new ArrayList<>();
        List<LayerItem> layerItems = gson.fromJson(dubboParams, new TypeToken<List<LayerItem>>() {
        }.getType());
        if (Objects.isNull(layerItems) || layerItems.isEmpty()) {
            return "";
        }
        for (LayerItem item : layerItems) {
            HttpJsonParamBo paramBo = parseLayerItem(item);
            paramList.add(paramBo);
        }
        rootArr.setChildList(paramList);
        paramBos.add(rootArr);
        return gson.toJson(paramBos);
    }

    public String transDubboResp2Http(String dubboResp) {
        if ("{}".equals(dubboResp)) {
            return "";
        }
        LayerItem layerItem = gson.fromJson(dubboResp, new TypeToken<LayerItem>() {
        }.getType());
        if (Objects.isNull(layerItem)) {
            return "";
        }
        List<HttpJsonParamBo> paramList = new ArrayList<>();
        paramList.add(parseLayerItem(layerItem));

        return gson.toJson(paramList);
    }

    public HttpJsonParamBo parseLayerItem(LayerItem layerItem) {
        HttpJsonParamBo jsonParamBo = new HttpJsonParamBo();
        if (Objects.isNull(layerItem)){
            return jsonParamBo;
        }
        jsonParamBo.setParamKey(layerItem.getItemName());
        jsonParamBo.setParamValue(layerItem.getDefaultValue());
        jsonParamBo.setParamName(layerItem.getItemName());
        jsonParamBo.setParamNote(layerItem.getDesc());
        jsonParamBo.setParamNotNull(layerItem.isRequired());
        jsonParamBo.setParamType(judgeParamType(layerItem.getItemClassStr()));

        List<HttpJsonParamBo> childList = new ArrayList<>();
        if (Objects.nonNull(layerItem.getItemValue()) && !layerItem.getItemValue().isEmpty()) {
            for (LayerItem item : layerItem.getItemValue()
            ) {
                HttpJsonParamBo jsonParamBo1 = parseLayerItem(item);
                childList.add(jsonParamBo1);
            }
        }
        jsonParamBo.setChildList(childList);
        return jsonParamBo;
    }

    public String judgeParamType(String typeStr) {
        String rt;
        switch (typeStr) {
            case "java.lang.String":
                rt = "0";
                break;
            case "java.io.File":
            case "org.springframework.web.multipart.MultipartFile":
                rt = "1";
                break;
            case "json":
                rt = "2";
                break;
            case "int":
            case "java.lang.Integer":
                rt = "3";
                break;
            case "float":
            case "java.lang.Float":
                rt = "4";
                break;
            case "double":
            case "java.lang.Double":
                rt = "5";
                break;
            case "java.util.Date":
                rt = "6";
                break;
            case "java.sql.Timestamp":
                rt = "7";
                break;
            case "boolean":
            case "java.lang.Boolean":
                rt = "8";
                break;
            case "byte":
            case "java.lang.Byte":
                rt = "9";
                break;
            case "short":
            case "java.lang.Short":
                rt = "10";
                break;
            case "long":
            case "java.lang.Long":
                rt = "11";
                break;
            case "java.util.List":
                rt = "12";
                break;
            default:
                rt = "13";
                break;
        }
        return rt;
    }
}

