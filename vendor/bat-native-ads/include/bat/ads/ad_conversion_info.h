/* Copyright (c) 2019 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

#ifndef BAT_ADS_AD_CONVERSION_INFO_H_
#define BAT_ADS_AD_CONVERSION_INFO_H_

#include <stdint.h>

#include <string>
#include <vector>

#include "bat/ads/export.h"
#include "bat/ads/result.h"

namespace ads {

struct ADS_EXPORT AdConversionInfo {
  AdConversionInfo();
  AdConversionInfo(
      const AdConversionInfo& info);
  ~AdConversionInfo();

  bool operator==(
      const AdConversionInfo& rhs) const;
  bool operator!=(
      const AdConversionInfo& rhs) const;

  enum class SortType {
    kNone = 0,
    kAscendingOrder,
    kDescendingOrder
  };

  std::string ToJson() const;
  Result FromJson(
      const std::string& json,
      std::string* error_description = nullptr);

  std::string creative_set_id;
  std::string type;
  std::string url_pattern;
  unsigned int observation_window = 0;
  uint64_t expiry_timestamp = 0;
};

using AdConversionList = std::vector<AdConversionInfo>;

}  // namespace ads

#endif  // BAT_ADS_AD_CONVERSION_INFO_H_
