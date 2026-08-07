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
#   action-step-<n>.sh    one per `run:` step of the root composite action
#   setup-step-<n>.sh     one per `run:` step of the setup composite action
require 'yaml'

out = ARGV[0] or abort 'usage: extract-ci-shell.rb <output-directory>'
Dir.mkdir(out) unless Dir.exist?(out)

def write(path, body)
  File.write(path, "#!/usr/bin/env bash\n#{body}")
end

actions = {
  'action' => YAML.safe_load(File.read('action.yml')),
  'setup' => YAML.safe_load(File.read('setup/action.yml'))
}

# The two public actions repeat metadata that a composite action cannot safely
# share through a relative `uses:` path: inside a published action that path
# resolves against the caller's checkout. Keep the copies mechanically aligned
# while the shell installer remains their shared implementation.
%w[version java-version].each do |input|
  root_contract = actions.fetch('action').fetch('inputs').fetch(input)
    .values_at('required', 'default')
  setup_contract = actions.fetch('setup').fetch('inputs').fetch(input)
    .values_at('required', 'default')
  abort "shared action input #{input} has drifted" unless root_contract == setup_contract
end

java_steps = actions.transform_values do |metadata|
  metadata.fetch('runs').fetch('steps').find { |step| step['uses'] == 'actions/setup-java@v5' }
end
abort 'shared setup-java step has drifted' unless java_steps.values.uniq.size == 1

install_steps = actions.transform_values do |metadata|
  metadata.fetch('runs').fetch('steps').find { |step| step['id'] == 'install' }
end
shared_install_contracts = install_steps.transform_values do |step|
  {
    'id' => step['id'],
    'shell' => step['shell'],
    'version' => step.fetch('env')['EVENTB_ANIMATE_VERSION'],
    'repository' => step.fetch('env')['EVENTB_ANIMATE_REPO'],
    'run' => step.fetch('run').sub('/../scripts/', '/scripts/')
  }
end
abort 'shared installer step has drifted' unless shared_install_contracts.values.uniq.size == 1
unless install_steps.fetch('setup').fetch('env')['EVENTB_ANIMATE_ADD_TO_PATH'] == 'true' &&
       !install_steps.fetch('action').fetch('env').key?('EVENTB_ANIMATE_ADD_TO_PATH')
  abort 'only the setup action may publish the launcher on PATH'
end

template = YAML.safe_load(File.read('.gitlab-ci-template.yml')).fetch('.eventb-animate')
write(File.join(out, 'gitlab-script.sh'), template.fetch('script').join("\n"))
File.write(File.join(out, 'gitlab-image'), template.fetch('image'))
File.write(File.join(out, 'gitlab-variables.env'),
           template.fetch('variables').map { |k, v| "#{k}=#{v}\n" }.join)

actions.each do |name, metadata|
  metadata.fetch('runs').fetch('steps').each_with_index do |step, i|
    next unless step['run']

    write(File.join(out, "#{name}-step-#{i}.sh"), step['run'])
  end
end
