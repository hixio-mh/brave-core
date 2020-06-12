/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

#if defined(BRAVE_CHROMIUM_BUILD)
#define GOOGLE_CHROME_BUILD
#endif

#include "../../../../../../../chrome/browser/ui/webui/settings/metrics_reporting_handler.h"

#if defined(BRAVE_CHROMIUM_BUILD)
#undef GOOGLE_CHROME_BUILD
#endif
