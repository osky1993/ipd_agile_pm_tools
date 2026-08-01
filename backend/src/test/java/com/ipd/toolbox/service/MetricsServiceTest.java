package com.ipd.toolbox.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.domain.entity.MetricSnapshot;
import com.ipd.toolbox.mapper.MetricSnapshotMapper;
import com.ipd.toolbox.mapper.MetricsMapper;
import com.ipd.toolbox.mapper.WorkItemMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 驾驶舱趋势（规划§7.4）：流入/关闭按时间戳回算、存量指标按每日快照、读取时 upsert 当天。 */
class MetricsServiceTest {

    private MetricsMapper metricsMapper;
    private MetricSnapshotMapper snapshotMapper;
    private MetricsService service;

    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        metricsMapper = mock(MetricsMapper.class);
        snapshotMapper = mock(MetricSnapshotMapper.class);
        service = new MetricsService(metricsMapper, mock(WorkItemMapper.class),
                snapshotMapper, mock(ReadinessService.class));

        when(metricsMapper.projectMetrics(1L)).thenReturn(Map.of(
                "open_defects", 2L, "requirement_total", 10L, "requirement_accepted", 4L));
        when(metricsMapper.criteriaStats(1L)).thenReturn(Map.of("total", 5L, "met", 3L));
        when(metricsMapper.defectInflowByDay(1L)).thenReturn(List.of(
                Map.of("d", today.minusDays(1).toString(), "cnt", 2L)));
        when(metricsMapper.defectClosedByDay(1L)).thenReturn(List.of(
                Map.of("d", today.toString(), "cnt", 1L)));
    }

    @Test
    void 首次读取_插入当日快照并对齐日期轴() {
        when(snapshotMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        MetricSnapshot snap = new MetricSnapshot();
        snap.setSnapDate(today);
        snap.setOpenDefects(2);
        snap.setCriteriaTotal(5);
        snap.setCriteriaMet(3);
        when(snapshotMapper.selectList(any(Wrapper.class))).thenReturn(List.of(snap));

        List<MetricsService.TrendPoint> points = service.trend(1L, 7);

        // 连续 7 天日期轴
        assertEquals(7, points.size());
        assertEquals(today.minusDays(6).toString(), points.get(0).date());
        assertEquals(today.toString(), points.get(6).date());

        // 流入/关闭按日对齐，无数据补 0
        assertEquals(2, points.get(5).defectInflow());
        assertEquals(1, points.get(6).defectClosed());
        assertEquals(0, points.get(0).defectInflow());

        // 快照有的日期带存量指标，没有的为 null
        assertEquals(3, points.get(6).criteriaMet());
        assertNull(points.get(0).criteriaMet());

        // 当日快照落库且数值来自业务统计
        ArgumentCaptor<MetricSnapshot> captor = ArgumentCaptor.forClass(MetricSnapshot.class);
        verify(snapshotMapper).insert(captor.capture());
        MetricSnapshot saved = captor.getValue();
        assertEquals(today, saved.getSnapDate());
        assertEquals(5, saved.getCriteriaTotal());
        assertEquals(3, saved.getCriteriaMet());
        assertEquals(2, saved.getOpenDefects());
        assertEquals(10, saved.getReqTotal());
        assertEquals(4, saved.getReqAccepted());
    }

    @Test
    void 同日再次读取_更新快照不重复插入() {
        MetricSnapshot existing = new MetricSnapshot();
        existing.setId(99L);
        existing.setProjectId(1L);
        existing.setSnapDate(today);
        when(snapshotMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(snapshotMapper.selectList(any(Wrapper.class))).thenReturn(List.of(existing));

        service.trend(1L, 7);

        verify(snapshotMapper, never()).insert(any(MetricSnapshot.class));
        verify(snapshotMapper).updateById(existing);
        assertEquals(3, existing.getCriteriaMet());
    }

    @Test
    void 项目不存在报错() {
        when(metricsMapper.projectMetrics(9L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.trend(9L, 30));
    }
}
