package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.domain.entity.Lesson;
import com.ipd.toolbox.service.LessonService;
import com.ipd.toolbox.service.RiskPatternService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 组织资产：经验教训库 + 跨项目风险模式库。 */
@RestController
@RequestMapping("/api/assets")
public class AssetsController {

    private final LessonService lessonService;
    private final RiskPatternService riskPatternService;

    public AssetsController(LessonService lessonService, RiskPatternService riskPatternService) {
        this.lessonService = lessonService;
        this.riskPatternService = riskPatternService;
    }

    @GetMapping("/lessons")
    public Result<List<Lesson>> lessons(@RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String category,
                                        @RequestParam(required = false) Long projectId) {
        return Result.ok(lessonService.search(keyword, category, projectId));
    }

    @PostMapping("/lessons")
    public Result<Lesson> createLesson(@RequestBody Lesson lesson) {
        return Result.ok(lessonService.create(lesson));
    }

    @DeleteMapping("/lessons/{id}")
    public Result<Void> deleteLesson(@PathVariable Long id) {
        lessonService.delete(id);
        return Result.ok();
    }

    /** 跨项目风险模式库（含 CLOSED 项目）：结局分布/处置时长/高频词。 */
    @GetMapping("/risk-patterns")
    public Result<RiskPatternService.Patterns> riskPatterns(
            @RequestParam(required = false) String keyword) {
        return Result.ok(riskPatternService.patterns(keyword));
    }
}
