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

package com.xiaomi.youpin.docean.mvc;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * @author goodjava@qq.com
 */
public abstract class Post {


    public static JsonArray getParams(HttpRequestMethod method, JsonElement arguments) {
        JsonArray array = new JsonArray();
        Class<?>[] types = method.getMethod().getParameterTypes();
        if (types.length > 0 && types[0] == MvcContext.class) {
            array.add(new Gson().fromJson("{}", JsonObject.class));
        }

        if (null == arguments) {
            return array;
        }

        if (arguments.isJsonObject()) {
            array.add(arguments);
        }

        if (arguments.isJsonArray()) {
            arguments.getAsJsonArray().forEach(it -> {
                array.add(it);
            });
        }

        if (arguments.isJsonPrimitive()) {
            array.add(arguments.getAsJsonPrimitive());
        }

        return array;
    }

}
