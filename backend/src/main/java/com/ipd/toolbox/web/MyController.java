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
public class MyController {

    private final MyService service;

    public MyController(MyService service) {
        this.service = service;
    }

    /** 「我的一天」：跨项目聚合当前用户的进行中/超期/待复测/临近事项与关键预警。 */
    @GetMapping("/today")
    public Result<MyService.Today> today() {
        Long uid = UserContext.currentUserId();
        if (uid == null) {
            throw new BusinessException(4010, "未登录");
        }
        return Result.ok(service.today(uid));
    }
}
