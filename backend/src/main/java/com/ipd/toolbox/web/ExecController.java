package com.ipd.toolbox.web;

import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.service.ExecService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exec")
public class ExecController {

    private final ExecService service;

    public ExecController(ExecService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public Result<ExecService.Overview> overview() {
        return Result.ok(service.overview());
    }
}
