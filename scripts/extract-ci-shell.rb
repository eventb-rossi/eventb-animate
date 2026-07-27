#!/usr/bin/env ruby
#
# Write the shell embedded in the two CI wrappers to files, so that it can be
# linted and executed like any other script. Both wrappers keep their shell
# inside YAML — the composite action because that is what a composite action
# is, the GitLab template because a remote `include:` brings in that one file —
# so this is the only way either reaches shellcheck or a test runner.
#
# Usage: extract-ci-shell.rb <output-directory>
#
# Writes, relative to that directory:
#   gitlab-script.sh      every `script:` entry of `.eventb-animate`, joined the
#                         way GitLab runs them — one shell, so the download
#                         block's variables reach the block that runs the jar
#   gitlab-image          the image the template declares
#   gitlab-variables.env  the template's `variables:` defaults, which GitLab
#                         injects and a bare `bash gitlab-script.sh` would not
#   action-step-<n>.sh    one per `run:` step of the composite action
require 'yaml'

out = ARGV[0] or abort 'usage: extract-ci-shell.rb <output-directory>'
Dir.mkdir(out) unless Dir.exist?(out)

def write(path, body)
  File.write(path, "#!/usr/bin/env bash\n#{body}")
end

template = YAML.safe_load(File.read('.gitlab-ci-template.yml')).fetch('.eventb-animate')
write(File.join(out, 'gitlab-script.sh'), template.fetch('script').join("\n"))
File.write(File.join(out, 'gitlab-image'), template.fetch('image'))
File.write(File.join(out, 'gitlab-variables.env'),
           template.fetch('variables').map { |k, v| "#{k}=#{v}\n" }.join)

YAML.safe_load(File.read('action.yml')).fetch('runs').fetch('steps').each_with_index do |step, i|
  next unless step['run']

  write(File.join(out, "action-step-#{i}.sh"), step['run'])
end
