# Cloud SQL (MySQL) for requests, decisions, verdicts and the outbox. Private IP only, regional, backed up.
resource "google_sql_database_instance" "guard" {
  name                = "cashout-fraud-guard"
  database_version    = "MYSQL_8_4"
  region              = var.region
  deletion_protection = true

  settings {
    tier              = var.db_tier
    availability_type = "REGIONAL"

    backup_configuration {
      enabled            = true
      binary_log_enabled = true
      start_time         = "02:00"
    }

    ip_configuration {
      ipv4_enabled    = false
      private_network = var.network
    }

    database_flags {
      name  = "transaction_isolation"
      value = "READ-COMMITTED"
    }
  }
}

resource "google_sql_database" "guard" {
  name     = "fraudguard"
  instance = google_sql_database_instance.guard.name
}

resource "random_password" "db" {
  length  = 32
  special = false
}

resource "google_sql_user" "guard" {
  name     = "fraudguard"
  instance = google_sql_database_instance.guard.name
  password = random_password.db.result
}

resource "google_secret_manager_secret" "db_password" {
  secret_id = "cashout-fraud-guard-db-password"
  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_version" "db_password" {
  secret      = google_secret_manager_secret.db_password.id
  secret_data = random_password.db.result
}

# The Claude key for the appeal reader. Its value is added out of band; without it the service uses the rule reader.
resource "google_secret_manager_secret" "anthropic_api_key" {
  secret_id = "cashout-fraud-guard-anthropic-api-key"
  replication {
    auto {}
  }
}

# Topics: activity comes in; decisions and verdicts go out through the outbox relay.
resource "google_pubsub_topic" "activity" {
  name = "player-activity"
}

resource "google_pubsub_topic" "decisions" {
  name = "cashout-decisions"
}

resource "google_pubsub_topic" "verdicts" {
  name = "analyst-verdicts"
}

resource "google_pubsub_topic" "dead_letter" {
  name = "cashout-fraud-guard-dead-letter"
}

resource "google_pubsub_subscription" "activity" {
  name                       = "cashout-fraud-guard-activity"
  topic                      = google_pubsub_topic.activity.id
  ack_deadline_seconds       = 30
  message_retention_duration = "604800s"

  dead_letter_policy {
    dead_letter_topic     = google_pubsub_topic.dead_letter.id
    max_delivery_attempts = 10
  }
}

resource "google_pubsub_subscription" "analyst_queue" {
  name                 = "analyst-queue"
  topic                = google_pubsub_topic.decisions.id
  filter               = "attributes.action = \"HOLD\""
  ack_deadline_seconds = 60
}

resource "google_artifact_registry_repository" "images" {
  repository_id = "cashout-fraud-guard"
  location      = var.region
  format        = "DOCKER"
}

# One service account, bound to the Kubernetes service account through Workload Identity.
resource "google_service_account" "guard" {
  account_id   = "cashout-fraud-guard"
  display_name = "Cashout fraud guard"
}

resource "google_project_iam_member" "sql_client" {
  project = var.project_id
  role    = "roles/cloudsql.client"
  member  = "serviceAccount:${google_service_account.guard.email}"
}

resource "google_pubsub_topic_iam_member" "publish" {
  for_each = {
    decisions = google_pubsub_topic.decisions.id
    verdicts  = google_pubsub_topic.verdicts.id
  }
  topic  = each.value
  role   = "roles/pubsub.publisher"
  member = "serviceAccount:${google_service_account.guard.email}"
}

resource "google_pubsub_subscription_iam_member" "consume_activity" {
  subscription = google_pubsub_subscription.activity.id
  role         = "roles/pubsub.subscriber"
  member       = "serviceAccount:${google_service_account.guard.email}"
}

resource "google_secret_manager_secret_iam_member" "read" {
  for_each = {
    db        = google_secret_manager_secret.db_password.id
    anthropic = google_secret_manager_secret.anthropic_api_key.id
  }
  secret_id = each.value
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.guard.email}"
}

resource "google_service_account_iam_member" "workload_identity" {
  service_account_id = google_service_account.guard.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.project_id}.svc.id.goog[${var.k8s_namespace}/cashout-fraud-guard]"
}
