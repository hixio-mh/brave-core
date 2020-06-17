/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

#ifndef BRAVE_COMPONENTS_BRAVE_ADS_BROWSER_ADS_DATABASE_H_
#define BRAVE_COMPONENTS_BRAVE_ADS_BROWSER_ADS_DATABASE_H_

#include <memory>

#include "base/compiler_specific.h"
#include "base/files/file_path.h"
#include "base/memory/memory_pressure_listener.h"
#include "base/sequence_checker.h"
#include "sql/database.h"
#include "sql/init_status.h"
#include "sql/meta_table.h"
#include "bat/ads/ads_client.h"

namespace brave_ads {

class AdsDatabase {
 public:
  explicit AdsDatabase(
      const base::FilePath& path);

  ~AdsDatabase();

  AdsDatabase(const AdsDatabase&) = delete;
  AdsDatabase& operator=(const AdsDatabase&) = delete;

  void RunTransaction(
      ads::DBTransactionPtr transaction,
      ads::DBCommandResponse* response);

 private:
  ads::DBCommandResponse::Status Initialize(
      const int32_t version,
      const int32_t compatible_version,
      ads::DBCommandResponse* response);

  ads::DBCommandResponse::Status Execute(
      ads::DBCommand* command);

  ads::DBCommandResponse::Status Run(
      ads::DBCommand* command);

  ads::DBCommandResponse::Status Read(
      ads::DBCommand* command,
      ads::DBCommandResponse* response);

  ads::DBCommandResponse::Status Migrate(
      const int32_t version,
      const int32_t compatible_version);

  void OnMemoryPressure(
      base::MemoryPressureListener::MemoryPressureLevel memory_pressure_level);

  const base::FilePath db_path_;
  sql::Database db_;
  sql::MetaTable meta_table_;
  bool is_initialized_;

  std::unique_ptr<base::MemoryPressureListener> memory_pressure_listener_;

  SEQUENCE_CHECKER(sequence_checker_);
};

}  // namespace brave_ads

#endif  // BRAVE_COMPONENTS_BRAVE_ADS_BROWSER_ADS_DATABASE_H_
