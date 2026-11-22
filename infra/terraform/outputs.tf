output "sql_connection_name" {
  value = google_sql_database_instance.guard.connection_name
}

output "service_account" {
  value = google_service_account.guard.email
}

output "image_repository" {
  value = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.images.repository_id}"
}
