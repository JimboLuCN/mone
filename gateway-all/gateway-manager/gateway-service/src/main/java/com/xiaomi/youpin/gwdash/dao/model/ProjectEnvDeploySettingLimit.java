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

package com.xiaomi.youpin.gwdash.dao.model;

import lombok.Data;
import org.nutz.dao.entity.annotation.Column;
import org.nutz.dao.entity.annotation.Id;
import org.nutz.dao.entity.annotation.Table;

/**
 * @author jiangzheng3
 */
@Data
@Table("project_env_deploy_setting_limit")
public class ProjectEnvDeploySettingLimit {
    @Id
    private long id;

    @Column("env_id")
    private long envId;

    @Column("cpu_limit")
    private int cpuLimit;

    @Column("memory_limit")
    private int memoryLimit;

    @Column("instance_limit")
    private int instanceLimit;

}
