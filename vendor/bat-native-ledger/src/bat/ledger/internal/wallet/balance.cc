/* Copyright (c) 2019 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

#include "bat/ledger/internal/wallet/balance.h"

#include <map>
#include <string>
#include <utility>
#include <vector>

#include "base/json/json_reader.h"
#include "base/strings/stringprintf.h"
#include "bat/ledger/global_constants.h"
#include "bat/ledger/internal/ledger_impl.h"
#include "bat/ledger/internal/uphold/uphold.h"
#include "bat/ledger/internal/request/request_util.h"
#include "bat/ledger/internal/static_values.h"
#include "bat/ledger/internal/state/state_util.h"
#include "net/http/http_status_code.h"

using std::placeholders::_1;
using std::placeholders::_2;
using std::placeholders::_3;

namespace braveledger_wallet {

Balance::Balance(bat_ledger::LedgerImpl* ledger) :
    uphold_(std::make_unique<braveledger_uphold::Uphold>(ledger)),
    ledger_(ledger) {
}

Balance::~Balance() {
}

void Balance::Fetch(ledger::FetchBalanceCallback callback) {
  // if we don't have user funds in anon card anymore
  // we can skip balance server ping
  if (!braveledger_state::GetFetchOldBalanceEnabled(ledger_)) {
    auto balance = ledger::Balance::New();
    balance->user_funds = "0";
    GetUnblindedTokens(std::move(balance), callback);
    return;
  }

  std::string payment_id = ledger_->GetPaymentId();

  std::string path = base::StringPrintf(
      "/wallet/%s/balance",
      payment_id.c_str());
  const std::string url = braveledger_request_util::BuildUrl(
      path,
      PREFIX_V2,
      braveledger_request_util::ServerTypes::BALANCE);
  auto load_callback = std::bind(&Balance::OnFetch,
                            this,
                            _1,
                            callback);
  ledger_->LoadURL(url, {}, "", "", ledger::UrlMethod::GET, load_callback);
}

void Balance::OnFetch(
    const ledger::UrlResponse& response,
    ledger::FetchBalanceCallback callback) {
  ledger::BalancePtr balance = ledger::Balance::New();
  BLOG(6, ledger::UrlResponseToString(__func__, response));
  if (response.status_code != net::HTTP_OK) {
    callback(ledger::Result::LEDGER_ERROR, std::move(balance));
    return;
  }

  base::Optional<base::Value> value = base::JSONReader::Read(response.body);
  if (!value || !value->is_dict()) {
    BLOG(0, "Response is not JSON");
    callback(ledger::Result::LEDGER_ERROR, std::move(balance));
    return;
  }

  base::DictionaryValue* dictionary = nullptr;
  if (!value->GetAsDictionary(&dictionary)) {
    BLOG(0, "Response is not JSON");
    callback(ledger::Result::LEDGER_ERROR, std::move(balance));
    return;
  }

  const auto* total = dictionary->FindStringKey("balance");
  double total_anon = 0.0;
  if (total) {
    total_anon = std::stod(*total);
  }
  balance->total = total_anon;

  const auto* funds = dictionary->FindStringKey("cardBalance");
  std::string user_funds = "0";
  if (funds) {
    user_funds = *funds;
  }
  balance->user_funds = user_funds;

  balance->wallets.insert(std::make_pair(ledger::kWalletAnonymous, total_anon));

  if (balance->total == 0.0) {
    braveledger_state::SetFetchOldBalanceEnabled(ledger_, false);
  }

  GetUnblindedTokens(std::move(balance), callback);
}

void Balance::GetUnblindedTokens(
    ledger::BalancePtr balance,
    ledger::FetchBalanceCallback callback) {
  if (!balance) {
    BLOG(0, "Balance is null");
    callback(ledger::Result::LEDGER_ERROR, std::move(balance));
    return;
  }

  auto tokens_callback = std::bind(&Balance::OnGetUnblindedTokens,
      this,
      *balance,
      callback,
      _1);
  ledger_->GetSpendableUnblindedTokensByBatchTypes(
      {ledger::CredsBatchType::PROMOTION},
      tokens_callback);
}

void Balance::OnGetUnblindedTokens(
    ledger::Balance info,
    ledger::FetchBalanceCallback callback,
    ledger::UnblindedTokenList list) {
  auto info_ptr = ledger::Balance::New(info);
  double total = 0.0;
  for (auto & item : list) {
    total+=item->value;
  }
  info_ptr->total += total;
  info_ptr->wallets.insert(std::make_pair(ledger::kWalletUnBlinded, total));
  ExternalWallets(std::move(info_ptr), callback);
}

void Balance::ExternalWallets(
    ledger::BalancePtr balance,
    ledger::FetchBalanceCallback callback) {
  if (!balance) {
    BLOG(0, "Balance is null");
    callback(ledger::Result::LEDGER_ERROR, std::move(balance));
    return;
  }
  auto tokens_callback = std::bind(&Balance::OnExternalWallets,
                                   this,
                                   *balance,
                                   callback,
                                   _1);

  ledger_->GetExternalWallets(tokens_callback);
}

void Balance::OnExternalWallets(
    ledger::Balance info,
    ledger::FetchBalanceCallback callback,
    std::map<std::string, ledger::ExternalWalletPtr> wallets) {
  if (wallets.size() == 0) {
    ledger::BalancePtr info_ptr = ledger::Balance::New(info);
    callback(ledger::Result::LEDGER_OK, std::move(info_ptr));
    return;
  }

  auto uphold_callback = std::bind(&Balance::OnUpholdFetchBalance,
                                   this,
                                   info,
                                   callback,
                                   _1,
                                   _2);

  uphold_->FetchBalance(std::move(wallets), uphold_callback);
}

void Balance::OnUpholdFetchBalance(ledger::Balance info,
                                   ledger::FetchBalanceCallback callback,
                                   ledger::Result result,
                                   double balance) {
  ledger::BalancePtr info_ptr = ledger::Balance::New(info);

  if (result == ledger::Result::LEDGER_ERROR) {
    BLOG(0, "Can't get uphold balance");
    callback(ledger::Result::LEDGER_ERROR, std::move(info_ptr));
    return;
  }

  info_ptr->wallets.insert(std::make_pair(ledger::kWalletUphold, balance));
  info_ptr->total += balance;
  callback(result, std::move(info_ptr));
}

// static
double Balance::GetPerWalletBalance(
    const std::string& type,
    base::flat_map<std::string, double> wallets) {
  if (type.empty() || wallets.size() == 0) {
    return 0.0;
  }

  for (const auto& wallet : wallets) {
    if (wallet.first == type) {
      return wallet.second;
    }
  }

  return  0.0;
}

}  // namespace braveledger_wallet
