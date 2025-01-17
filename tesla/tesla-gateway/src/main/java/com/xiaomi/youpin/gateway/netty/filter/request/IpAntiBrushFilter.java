/*
 *  Copyright 2020 Xiaomi
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.xiaomi.youpin.gateway.netty.filter.request;

import com.xiaomi.data.push.redis.Redis;
import com.xiaomi.youpin.gateway.common.FilterOrder;
import com.xiaomi.youpin.gateway.common.HttpResponseUtils;
import com.xiaomi.youpin.gateway.common.Keys;
import com.xiaomi.youpin.gateway.common.Utils;
import com.xiaomi.youpin.gateway.filter.FilterContext;
import com.xiaomi.youpin.gateway.filter.Invoker;
import com.xiaomi.youpin.gateway.filter.RequestFilter;
import com.xiaomi.youpin.infra.rpc.Result;
import com.xiaomi.youpin.infra.rpc.errors.GeneralCodes;
import com.youpin.xiaomi.tesla.bo.ApiInfo;
import com.youpin.xiaomi.tesla.bo.Flag;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@FilterOrder(Integer.MIN_VALUE + 11)
public class IpAntiBrushFilter extends RequestFilter {
    static final String ipTag = "IP-ANTI-BRUSH-TIME";
    @Autowired
    private Redis redis;

    @Override
    public FullHttpResponse doFilter(FilterContext context, Invoker invoker, ApiInfo apiInfo, FullHttpRequest request) {

        if (apiInfo.isAllow(Flag.IP_ANTI_BRUSH)) {

            if (StringUtils.isEmpty(context.getIp())) {
                return invoker.doInvoker(context, apiInfo, request);
            }

            String key = Keys.ipAntiBrushKey(Utils.md5(apiInfo.getUrl()), context.getIp());
            try {
                long value = redis.incr(key);
                if (value == 1L) {
                    String ipTimeStr = context.getAttachment(ipTag, "60");
                    redis.expire(key, Integer.parseInt(ipTimeStr));
                }
                if (value > apiInfo.getIpAntiBrushLimit()) {
                    //429
                    return HttpResponseUtils.create(Result.fail(GeneralCodes.TOO_MANY_REQUESTS, "您的操作太频繁啦，请稍后重试哦~~", context.getIp()));
                }
            } catch (Throwable ex) {
                log.warn("redis incr error:{}", ex.getMessage());
            }
        }

        return invoker.doInvoker(context, apiInfo, request);
    }

}
