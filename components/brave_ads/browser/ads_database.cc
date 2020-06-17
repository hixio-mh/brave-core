/* Copyright (c) 2020 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

#include <string>
#include <utility>
#include <vector>

#include "base/bind.h"
#include "base/files/file_util.h"
#include "brave/components/brave_ads/browser/ads_database.h"
#include "sql/statement.h"
#include "sql/transaction.h"

namespace brave_ads {

namespace {

void HandleBinding(
    sql::Statement* statement,
    const ads::DBCommandBinding& binding) {
  DCHECK(statement);

  switch (binding.value->which()) {
    case ads::DBValue::Tag::STRING_VALUE: {
      statement->BindString(binding.index, binding.value->get_string_value());
      return;
    }

    case ads::DBValue::Tag::INT_VALUE: {
      statement->BindInt(binding.index, binding.value->get_int_value());
      return;
    }

    case ads::DBValue::Tag::INT64_VALUE: {
      statement->BindInt64(binding.index, binding.value->get_int64_value());
      return;
    }

    case ads::DBValue::Tag::DOUBLE_VALUE: {
      statement->BindDouble(binding.index, binding.value->get_double_value());
      return;
    }

    case ads::DBValue::Tag::BOOL_VALUE: {
      statement->BindBool(binding.index, binding.value->get_bool_value());
      return;
    }

    case ads::DBValue::Tag::NULL_VALUE: {
      statement->BindNull(binding.index);
      return;
    }
  }
}

ads::DBRecordPtr CreateRecord(
    sql::Statement* statement,
    const std::vector<ads::DBCommand::RecordBindingType>& bindings) {
  DCHECK(statement);

  auto record = ads::DBRecord::New();

  int column = 0;

  for (const auto& binding : bindings) {
    auto value = ads::DBValue::New();
    switch (binding) {
      case ads::DBCommand::RecordBindingType::STRING_TYPE: {
        value->set_string_value(statement->ColumnString(column));
        break;
      }

      case ads::DBCommand::RecordBindingType::INT_TYPE: {
        value->set_int_value(statement->ColumnInt(column));
        break;
      }

      case ads::DBCommand::RecordBindingType::INT64_TYPE: {
        value->set_int64_value(statement->ColumnInt64(column));
        break;
      }

      case ads::DBCommand::RecordBindingType::DOUBLE_TYPE: {
        value->set_double_value(statement->ColumnDouble(column));
        break;
      }

      case ads::DBCommand::RecordBindingType::BOOL_TYPE: {
        value->set_bool_value(statement->ColumnBool(column));
        break;
      }
    }

    record->fields.push_back(std::move(value));
    column++;
  }

  return record;
}

}  // namespace

AdsDatabase::AdsDatabase(
    const base::FilePath& path)
    : db_path_(path),
      is_initialized_(false) {
  DETACH_FROM_SEQUENCE(sequence_checker_);
}

AdsDatabase::~AdsDatabase() = default;

void AdsDatabase::RunTransaction(
    ads::DBTransactionPtr transaction,
    ads::DBCommandResponse* response) {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);

  DCHECK(response);

  if (!db_.is_open() && !db_.Open(db_path_)) {
    response->status = ads::DBCommandResponse::Status::INITIALIZATION_ERROR;
    return;
  }

  sql::Transaction committer(&db_);
  if (!committer.Begin()) {
    response->status = ads::DBCommandResponse::Status::TRANSACTION_ERROR;
    return;
  }

  for (const auto& command : transaction->commands) {
    ads::DBCommandResponse::Status status;

    VLOG(8) << "Database query: " << command->command;

    switch (command->type) {
      case ads::DBCommand::Type::INITIALIZE: {
        status = Initialize(transaction->version,
            transaction->compatible_version, response);
        break;
      }

      case ads::DBCommand::Type::READ: {
        status = Read(command.get(), response);
        break;
      }

      case ads::DBCommand::Type::EXECUTE: {
        status = Execute(command.get());
        break;
      }

      case ads::DBCommand::Type::RUN: {
        status = Run(command.get());
        break;
      }

      case ads::DBCommand::Type::MIGRATE: {
        status = Migrate(transaction->version, transaction->compatible_version);
        break;
      }
    }

    if (status != ads::DBCommandResponse::Status::RESPONSE_OK) {
      committer.Rollback();
      response->status = status;
      return;
    }
  }

  if (!committer.Commit()) {
    response->status = ads::DBCommandResponse::Status::TRANSACTION_ERROR;
  }
}

ads::DBCommandResponse::Status AdsDatabase::Initialize(
    const int32_t version,
    const int32_t compatible_version,
    ads::DBCommandResponse* response) {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);

  DCHECK(response);

  int table_version = 0;

  if (!is_initialized_) {
    bool table_exists = false;
    if (meta_table_.DoesTableExist(&db_)) {
      table_exists = true;
    }

    if (!meta_table_.Init(&db_, version, compatible_version)) {
      return ads::DBCommandResponse::Status::INITIALIZATION_ERROR;
    }

    if (table_exists) {
      table_version = meta_table_.GetVersionNumber();
    }

    is_initialized_ = true;
    memory_pressure_listener_.reset(new base::MemoryPressureListener(
        base::Bind(&AdsDatabase::OnMemoryPressure, base::Unretained(this))));
  } else {
    table_version = meta_table_.GetVersionNumber();
  }

  auto value = ads::DBValue::New();
  value->set_int_value(table_version);
  auto result = ads::DBCommandResult::New();
  result->set_value(std::move(value));
  response->result = std::move(result);

  return ads::DBCommandResponse::Status::RESPONSE_OK;
}

ads::DBCommandResponse::Status AdsDatabase::Execute(
    ads::DBCommand* command) {
  DCHECK(command);

  if (!is_initialized_) {
    return ads::DBCommandResponse::Status::INITIALIZATION_ERROR;
  }

  bool result = db_.Execute(command->command.c_str());

  if (!result) {
    VLOG(0) << "Database execute error: " << db_.GetErrorMessage();

    return ads::DBCommandResponse::Status::COMMAND_ERROR;
  }

  return ads::DBCommandResponse::Status::RESPONSE_OK;
}

ads::DBCommandResponse::Status AdsDatabase::Run(
    ads::DBCommand* command) {
  DCHECK(command);

  if (!is_initialized_) {
    return ads::DBCommandResponse::Status::INITIALIZATION_ERROR;
  }

  sql::Statement statement(db_.GetUniqueStatement(command->command.c_str()));

  for (const auto& binding : command->bindings) {
    HandleBinding(&statement, *binding.get());
  }

  if (!statement.Run()) {
    VLOG(0) << "Database run error: " << db_.GetErrorMessage() << " ("
        << db_.GetErrorCode() << ")";

    return ads::DBCommandResponse::Status::COMMAND_ERROR;
  }

  return ads::DBCommandResponse::Status::RESPONSE_OK;
}

ads::DBCommandResponse::Status AdsDatabase::Read(
    ads::DBCommand* command,
    ads::DBCommandResponse* response) {
  if (!is_initialized_) {
    return ads::DBCommandResponse::Status::INITIALIZATION_ERROR;
  }

  if (!command || !response) {
    return ads::DBCommandResponse::Status::RESPONSE_ERROR;
  }

  sql::Statement statement(db_.GetUniqueStatement(command->command.c_str()));

  for (const auto& binding : command->bindings) {
    HandleBinding(&statement, *binding.get());
  }

  auto result = ads::DBCommandResult::New();
  result->set_records(std::vector<ads::DBRecordPtr>());
  response->result = std::move(result);
  while (statement.Step()) {
    response->result->get_records().push_back(
        CreateRecord(&statement, command->record_bindings));
  }

  return ads::DBCommandResponse::Status::RESPONSE_OK;
}

ads::DBCommandResponse::Status AdsDatabase::Migrate(
    const int32_t version,
    const int32_t compatible_version) {
  if (!is_initialized_) {
    return ads::DBCommandResponse::Status::INITIALIZATION_ERROR;
  }

  meta_table_.SetVersionNumber(version);
  meta_table_.SetCompatibleVersionNumber(compatible_version);

  return ads::DBCommandResponse::Status::RESPONSE_OK;
}

void AdsDatabase::OnMemoryPressure(
    base::MemoryPressureListener::MemoryPressureLevel memory_pressure_level) {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  db_.TrimMemory();
}

}  // namespace brave_ads
