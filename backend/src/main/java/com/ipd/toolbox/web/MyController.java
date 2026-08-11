package com.ipd.toolbox.web;

import com.ipd.toolbox.common.BusinessException;
import com.ipd.toolbox.common.Result;
import com.ipd.toolbox.security.UserContext;
import com.ipd.toolbox.service.MyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/my")
/**
 * 个人工作台控制器：汇总当前用户的“今日视图”，用于我的代办与预警快速定位。
 * 典型入口是 Dashboard 顶部卡片与“我的事项”页。
 */
public class MyController {

    private final MyService service;

    public MyController(MyService service) {
        this.service = service;
    }

    /** 个人工作台：返回我的当前代办集合（进行中、超期、待复测、临近到期）和个人风险预警。 */
    @GetMapping("/today")
    public Result<MyService.Today> today() {
        Long uid = UserContext.currentUserId();
        if (uid == null) {
            throw new BusinessException(4010, "未登录");
        }
        return Result.ok(service.today(uid));
    }
}
