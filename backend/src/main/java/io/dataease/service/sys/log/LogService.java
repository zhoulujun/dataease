package io.dataease.service.sys.log;


import cn.hutool.core.date.DateUtil;

import com.google.gson.Gson;
import io.dataease.auth.api.dto.CurrentUserDto;
import io.dataease.commons.constants.ParamConstants;
import io.dataease.commons.constants.SysLogConstants;
import io.dataease.commons.utils.AuthUtils;
import io.dataease.commons.utils.BeanUtils;
import io.dataease.commons.utils.IPUtils;
import io.dataease.commons.utils.ServletUtils;
import io.dataease.controller.sys.base.ConditionEntity;
import io.dataease.controller.sys.request.KeyGridRequest;
import io.dataease.dto.SysLogDTO;
import io.dataease.dto.SysLogGridDTO;
import io.dataease.dto.log.FolderItem;
import io.dataease.exception.DataEaseException;
import io.dataease.ext.ExtSysLogMapper;
import io.dataease.ext.query.GridExample;
import io.dataease.i18n.Translator;
import io.dataease.plugins.common.base.domain.SysLogExample;
import io.dataease.plugins.common.base.domain.SysLogWithBLOBs;
import io.dataease.plugins.common.base.mapper.SysLogMapper;
import io.dataease.service.system.SystemParameterService;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.hssf.usermodel.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class LogService {

    private static final String LOG_RETENTION = "30";
    private Gson gson = new Gson();

    // 仪表板的额外操作 分享以及公共链接
    private static Integer[] panel_ext_ope = {4, 5, 8, 9, 10};

    private static Integer[] link_ext_ope = {13, 14};

    // 驱动文件操作 上传， 删除
    private static Integer[] driver_file_ope = {11, 3};

    private static Integer[] COMMON_SOURCE = {1, 2, 3, 6, 7, 8, 9};

    // 增 改 删  针对公共资源的操作
    private static Integer[] COMMON_SOURCE_OPERATE = {1, 2, 3};

    // 授权相关操作
    private static Integer[] AUTH_OPERATE = {6, 7};

    // 授权相关资源 数据源 仪表板 数据集 菜单
    private static Integer[] AUTH_SOURCE = {1, 2, 3, 11};


    @Resource
    private SysLogMapper sysLogMapper;

    @Resource
    private ExtSysLogMapper extSysLogMapper;

    @Resource
    private LogManager logManager;

    @Resource
    private SystemParameterService systemParameterService;

    public void cleanDisusedLog() {
        String value = systemParameterService.getValue(ParamConstants.BASIC.LOG_TIME_OUT.getValue());
        value = StringUtils.isBlank(value) ? LOG_RETENTION : value;
        int logRetention = Integer.parseInt(value);
        Calendar instance = Calendar.getInstance();

        Calendar startInstance = (Calendar) instance.clone();
        startInstance.add(Calendar.DATE, -logRetention);
        startInstance.set(Calendar.HOUR_OF_DAY, 0);
        startInstance.set(Calendar.MINUTE, 0);
        startInstance.set(Calendar.SECOND, 0);
        startInstance.set(Calendar.MILLISECOND, -1);
        long timeInMillis = startInstance.getTimeInMillis();
        SysLogExample example = new SysLogExample();
        example.createCriteria().andTimeLessThan(timeInMillis);
        sysLogMapper.deleteByExample(example);
    }


    public KeyGridRequest logRetentionProxy(KeyGridRequest request) {
        String value = systemParameterService.getValue(ParamConstants.BASIC.LOG_TIME_OUT.getValue());
        value = StringUtils.isBlank(value) ? LOG_RETENTION : value;
        int logRetention = Integer.parseInt(value);
        Calendar instance = Calendar.getInstance();

        Calendar startInstance = (Calendar) instance.clone();
        startInstance.add(Calendar.DATE, -logRetention);
        startInstance.set(Calendar.HOUR_OF_DAY, 0);
        startInstance.set(Calendar.MINUTE, 0);
        startInstance.set(Calendar.SECOND, 0);
        long startTime = startInstance.getTimeInMillis();

        Calendar endInstance = (Calendar) instance.clone();
        endInstance.add(Calendar.DATE, 1);
        endInstance.set(Calendar.HOUR_OF_DAY, 0);
        endInstance.set(Calendar.MINUTE, 0);
        endInstance.set(Calendar.SECOND, 0);
        long endTime = endInstance.getTimeInMillis();


        List<ConditionEntity> conditions = request.getConditions();
        if (CollectionUtils.isNotEmpty(conditions) && conditions.stream().anyMatch(condition -> StringUtils.equals("time", condition.getField()))) {
            conditions.forEach(condition -> {
                if (StringUtils.equals("time", condition.getField()) && startTime > ((List<Long>) condition.getValue()).get(0)) {
                    ((List<Long>) condition.getValue()).set(0, startTime);
                }
            });
        } else {
            ConditionEntity conditionEntity = new ConditionEntity();
            conditionEntity.setField("time");
            conditionEntity.setOperator("between");
            List<Long> times = new ArrayList<>();
            times.add(startTime);
            times.add(endTime);
            conditionEntity.setValue(times);
            conditions.add(conditionEntity);
        }
        return request;
    }


    public List<SysLogGridDTO> query(KeyGridRequest request) {


        request = detailRequest(request);
        String keyWord = request.getKeyWord();
        List<String> ids = null;
        GridExample gridExample = request.convertExample();
        gridExample.setExtendCondition(keyWord);

        LogQueryParam logQueryParam = gson.fromJson(gson.toJson(gridExample), LogQueryParam.class);
        if (StringUtils.isNotBlank(keyWord)) {
            List<FolderItem> types = types();
            ids = types.stream().filter(item -> item.getName().toLowerCase().contains(keyWord.toLowerCase())).map(FolderItem::getId).collect(Collectors.toList());
            if (CollectionUtils.isNotEmpty(ids))
                logQueryParam.setUnionIds(ids);
        }
        List<SysLogWithBLOBs> voLogs = extSysLogMapper.query(logQueryParam);
        List<SysLogGridDTO> dtos = voLogs.stream().map(this::convertDTO).collect(Collectors.toList());
        return dtos;
    }

    private KeyGridRequest detailRequest(KeyGridRequest request) {
        List<ConditionEntity> conditions = request.getConditions();
        if (CollectionUtils.isNotEmpty(conditions)) {

            ConditionEntity uninCondition = null;
            int matchIndex = -1;
            for (int i = 0; i < conditions.size(); i++) {
                ConditionEntity conditionEntity = conditions.get(i);
                String field = conditionEntity.getField();
                Object value = conditionEntity.getValue();

                if (StringUtils.isNotBlank(field) && StringUtils.equals("optype", field) && ObjectUtils.isNotEmpty(value)) {
                    matchIndex = i;
                    uninCondition = new ConditionEntity();

                    List<String> values = (List<String>) value;
                    uninCondition.setField("concat(operate_type, '-de-', source_type)");

                    List<String> uninValue = values.stream().map(v -> v.replace("-", "-de-")).collect(Collectors.toList());

                    uninCondition.setValue(uninValue);
                    uninCondition.setOperator(conditionEntity.getOperator());
                }
            }
            if (matchIndex >= 0) {
                conditions.remove(matchIndex);

                if (ObjectUtils.isNotEmpty(uninCondition)) conditions.add(uninCondition);
            }
        }
        return request;
    }


    public List<FolderItem> types() {


        List<FolderItem> results = new ArrayList<>();


        for (int i = 0; i < COMMON_SOURCE.length; i++) {

            Integer sourceVal = COMMON_SOURCE[i];
            String sourceTypeName = SysLogConstants.sourceTypeName(sourceVal);

            for (int j = 0; j < COMMON_SOURCE_OPERATE.length; j++) {

                Integer operateVal = COMMON_SOURCE_OPERATE[j];
                String operateTypeName = SysLogConstants.operateTypeName(operateVal);
                FolderItem folderItem = new FolderItem();
                folderItem.setId(operateVal + "-" + sourceVal);
                folderItem.setName(Translator.get(operateTypeName) + Translator.get(sourceTypeName));
                results.add(folderItem);
            }
        }


        for (int i = 0; i < driver_file_ope.length; i++) {
            SysLogConstants.SOURCE_TYPE sourceType = SysLogConstants.SOURCE_TYPE.DRIVER_FILE;
            FolderItem folderItem = new FolderItem();
            folderItem.setId(driver_file_ope[i] + "-" + sourceType.getValue());
            String operateTypeName = SysLogConstants.operateTypeName(driver_file_ope[i]);
            String sourceTypeName = sourceType.getName();
            folderItem.setName(Translator.get(operateTypeName) + Translator.get(sourceTypeName));
            results.add(folderItem);
        }

        for (int i = 0; i < AUTH_SOURCE.length; i++) {
            Integer sourceVal = AUTH_SOURCE[i];
            String sourceTypeName = SysLogConstants.sourceTypeName(sourceVal);

            for (int j = 0; j < AUTH_OPERATE.length; j++) {

                Integer operateVal = AUTH_OPERATE[j];
                String operateTypeName = SysLogConstants.operateTypeName(operateVal);
                FolderItem folderItem = new FolderItem();
                folderItem.setId(operateVal + "-" + sourceVal);
                folderItem.setName(Translator.get(operateTypeName) + Translator.get(sourceTypeName));
                results.add(folderItem);
            }
        }

        for (int i = 0; i < panel_ext_ope.length; i++) {
            SysLogConstants.SOURCE_TYPE sourceType = SysLogConstants.SOURCE_TYPE.PANEL;
            FolderItem folderItem = new FolderItem();
            folderItem.setId(panel_ext_ope[i] + "-" + sourceType.getValue());
            String operateTypeName = SysLogConstants.operateTypeName(panel_ext_ope[i]);
            String sourceTypeName = sourceType.getName();
            folderItem.setName(Translator.get(operateTypeName) + Translator.get(sourceTypeName));
            results.add(folderItem);
        }

        for (int i = 0; i < link_ext_ope.length; i++) {
            SysLogConstants.SOURCE_TYPE sourceType = SysLogConstants.SOURCE_TYPE.LINK;
            FolderItem folderItem = new FolderItem();
            folderItem.setId(link_ext_ope[i] + "-" + sourceType.getValue());
            String operateTypeName = SysLogConstants.operateTypeName(link_ext_ope[i]);
            String sourceTypeName = sourceType.getName();
            folderItem.setName(Translator.get(operateTypeName) + Translator.get(sourceTypeName));
            results.add(folderItem);
        }

        FolderItem userLogin = new FolderItem();
        SysLogConstants.OPERATE_TYPE operateTypeLogin = SysLogConstants.OPERATE_TYPE.LOGIN;
        SysLogConstants.SOURCE_TYPE sourceTypeLogin = SysLogConstants.SOURCE_TYPE.USER;
        userLogin.setId(operateTypeLogin.getValue() + "-" + sourceTypeLogin.getValue());
        String operateTypeName = SysLogConstants.operateTypeName(operateTypeLogin.getValue());
        String sourceTypeName = sourceTypeLogin.getName();
        userLogin.setName(Translator.get(operateTypeName) + Translator.get(sourceTypeName));
        results.add(userLogin);

        List<FolderItem> folderItems = viewPanelTypes();
        results.addAll(folderItems);
        results.addAll(viewRelativeTypes());
        results.addAll(bindUserTypes());
        return results;
    }

    private List<FolderItem> viewRelativeTypes() {
        Integer[] opTypes = new Integer[]{15};
        Integer[] sourceTypes = new Integer[]{4};
        return typesByArr(opTypes, sourceTypes);
    }

    private List<FolderItem> typesByArr(Integer[] opTypes, Integer[] sourceTypes) {
        List<FolderItem> results = new ArrayList<>();
        for (int i = 0; i < sourceTypes.length; i++) {
            Integer sourceVal = sourceTypes[i];
            String sourceTypeName = SysLogConstants.sourceTypeName(sourceVal);

            for (int j = 0; j < opTypes.length; j++) {

                Integer operateVal = opTypes[j];

                String operateTypeName = SysLogConstants.operateTypeName(operateVal);
                FolderItem folderItem = new FolderItem();
                folderItem.setId(operateVal + "-" + sourceVal);
                folderItem.setName(Translator.get(operateTypeName) + Translator.get(sourceTypeName));
                results.add(folderItem);
            }
        }
        return results;
    }

    private List<FolderItem> viewPanelTypes() {
        Integer[] opTypes = new Integer[]{13, 14};
        Integer[] sourceTypes = new Integer[]{3};
        return typesByArr(opTypes, sourceTypes);
    }

    private List<FolderItem> bindUserTypes() {
        Integer[] opTypes = new Integer[]{16, 17};
        Integer[] sourceTypes = new Integer[]{6};
        return typesByArr(opTypes, sourceTypes);
    }

    public SysLogGridDTO convertDTO(SysLogWithBLOBs vo) {
        SysLogGridDTO sysLogGridDTO = new SysLogGridDTO();
        sysLogGridDTO.setOpType(SysLogConstants.operateTypeName(vo.getOperateType()));
        sysLogGridDTO.setSourceType(SysLogConstants.sourceTypeName(vo.getSourceType()));
        sysLogGridDTO.setTime(vo.getTime());
        sysLogGridDTO.setUser(vo.getNickName());
        sysLogGridDTO.setDetail(logManager.detailInfo(vo));
        sysLogGridDTO.setIp(vo.getIp());
        return sysLogGridDTO;
    }

    public void saveLog(SysLogDTO sysLogDTO) {
        CurrentUserDto user = AuthUtils.getUser();
        SysLogWithBLOBs sysLogWithBLOBs = BeanUtils.copyBean(new SysLogWithBLOBs(), sysLogDTO);
        if (CollectionUtils.isNotEmpty(sysLogDTO.getPositions())) {
            sysLogWithBLOBs.setPosition(gson.toJson(sysLogDTO.getPositions()));
        }
        if (CollectionUtils.isNotEmpty(sysLogDTO.getRemarks())) {
            sysLogWithBLOBs.setRemark(gson.toJson(sysLogDTO.getRemarks()));
        }
        sysLogWithBLOBs.setTime(System.currentTimeMillis());
        if (ObjectUtils.isNotEmpty(user)) {
            sysLogWithBLOBs.setUserId(user.getUserId());
            sysLogWithBLOBs.setLoginName(user.getUsername());
            sysLogWithBLOBs.setNickName(user.getNickName());
        } else if (sysLogDTO.getOperateType() == SysLogConstants.OPERATE_TYPE.LOGIN.getValue()) {
            sysLogWithBLOBs.setUserId(Long.parseLong(sysLogDTO.getSourceId()));
            sysLogWithBLOBs.setLoginName(sysLogDTO.getSourceName());
            sysLogWithBLOBs.setNickName(sysLogDTO.getSourceName());
        }
        sysLogWithBLOBs.setIp(IPUtils.get());

        sysLogMapper.insert(sysLogWithBLOBs);
    }


    public void exportExcel(KeyGridRequest request) throws Exception {
        request = logRetentionProxy(request);
        request = detailRequest(request);
        String keyWord = request.getKeyWord();
        List<String> ids = null;
        HttpServletResponse response = ServletUtils.response();
        OutputStream outputStream = response.getOutputStream();
        try {
            GridExample gridExample = request.convertExample();
            gridExample.setExtendCondition(keyWord);
            LogQueryParam logQueryParam = gson.fromJson(gson.toJson(gridExample), LogQueryParam.class);
            if (StringUtils.isNotBlank(keyWord)) {
                List<FolderItem> types = types();
                ids = types.stream().filter(item -> item.getName().toLowerCase().contains(keyWord.toLowerCase())).map(FolderItem::getId).collect(Collectors.toList());
                if (CollectionUtils.isNotEmpty(ids))
                    logQueryParam.setUnionIds(ids);
            }
            List<SysLogWithBLOBs> lists = extSysLogMapper.query(logQueryParam);
            List<String[]> details = lists.stream().map(item -> {
                String operateTypeName = SysLogConstants.operateTypeName(item.getOperateType());
                String sourceTypeName = SysLogConstants.sourceTypeName(item.getSourceType());
                String[] row = new String[5];
                row[0] = Translator.get(operateTypeName) + " " + Translator.get(sourceTypeName);
                row[1] = logManager.detailInfo(item);
                row[2] = item.getNickName();
                row[3] = item.getIp();
                row[4] = DateUtil.formatDateTime(new Date(item.getTime()));
                return row;
            }).collect(Collectors.toList());
            String[] headArr = {"操作类型", "详情", "用户", "IP地址", "时间"};
            details.add(0, headArr);


            XSSFWorkbook wb = new XSSFWorkbook();
            //明细sheet
            XSSFSheet detailsSheet = wb.createSheet("数据");

            //给单元格设置样式
            XSSFCellStyle cellStyle = wb.createCellStyle();
            XSSFFont font = wb.createFont();
            //设置字体大小
            font.setFontHeightInPoints((short) 12);
            //设置字体加粗
            font.setBold(true);
            //给字体设置样式
            cellStyle.setFont(font);
            //设置单元格背景颜色
            cellStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            //设置单元格填充样式(使用纯色背景颜色填充)
            cellStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            if (CollectionUtils.isNotEmpty(details)) {
                for (int i = 0; i < details.size(); i++) {
                    XSSFRow row = detailsSheet.createRow(i);
                    String[] rowData = details.get(i);
                    if (rowData != null) {
                        for (int j = 0; j < rowData.length; j++) {
                            XSSFCell cell = row.createCell(j);
                            cell.setCellValue(rowData[j]);
                            if (i == 0) {// 头部
                                cell.setCellStyle(cellStyle);
                                //设置列的宽度
                                detailsSheet.setColumnWidth(j, 255 * 20);
                            }
                        }
                    }
                }
            }

            response.setContentType("application/vnd.ms-excel");
            //文件名称
            String fileName = "DataEase操作日志";
            String encodeFileName = URLEncoder.encode(fileName, "UTF-8");
            response.setHeader("Content-disposition", "attachment;filename=" + encodeFileName + ".xlsx");
            wb.write(outputStream);
            outputStream.flush();
            outputStream.close();
        } catch (Exception e) {
            DataEaseException.throwException(e);
        }
    }


}
