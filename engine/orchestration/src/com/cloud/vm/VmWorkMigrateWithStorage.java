// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.vm;

import java.util.Map;

import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;

public class VmWorkMigrateWithStorage extends VmWork {
    private static final long serialVersionUID = -5626053872453569165L;

    long srcHostId;
    long destHostId;
    Map<Volume, StoragePool> volumeToPool;

    public VmWorkMigrateWithStorage(long userId, long accountId, long vmId, long srcHostId,
            long destHostId, Map<Volume, StoragePool> volumeToPool) {

        super(userId, accountId, vmId);

        this.srcHostId = srcHostId;
        this.destHostId = destHostId;
        this.volumeToPool = volumeToPool;
    }

    public long getSrcHostId() {
        return this.srcHostId;
    }

    public long getDestHostId() {
        return this.destHostId;
    }

    public Map<Volume, StoragePool> getVolumeToPool() {
        return this.volumeToPool;
    }
}
