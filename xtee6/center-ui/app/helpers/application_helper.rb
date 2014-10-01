java_import Java::java.security.cert.X509Certificate

java_import Java::ee.cyber.sdsb.common.util.CryptoUtils

# Methods added to this helper will be available to all templates in
# the application.

module ApplicationHelper

  include BaseHelper

  private

  def menu_items
    result = []

    configuration_submenu_items = get_configuration_submenu_items()
    if can_see_menu_item?(configuration_submenu_items)
      result << SubMenu.new(t('menu.configuration.title'),
          configuration_submenu_items)
    end

    management_submenu_items = get_management_submenu_items()
    if can_see_menu_item?(management_submenu_items)
      result << SubMenu.new(t('menu.management.title'),
          management_submenu_items)
    end

    result << SubMenu.new(t('menu.help.title'),
        [ MenuItem.new(t('menu.help.version'), :about) ])

    return result
  end

  def get_configuration_submenu_items
    return [
      MenuItem.new(t('menu.configuration.member'), :members, :view_members),
      MenuItem.new(t('menu.configuration.securityserver'), :securityservers,
          :view_security_servers),
      MenuItem.new(t('menu.configuration.group'), :groups,
          :view_global_groups),
      MenuItem.new(t('menu.configuration.central_service'),
          :central_services, :view_central_services),
      MenuItem.new(t('menu.configuration.pki'), :pkis, :view_approved_cas),
      MenuItem.new(t('menu.configuration.tsp'), :tsps, :view_approved_tsps),
    ]
  end

  def get_management_submenu_items
    result = []
    result << MenuItem.new(t('menu.management.request'), :requests,
        :view_management_requests)

    if can_import_V5_data?
      result << MenuItem.new(t('menu.management.import_v5'), :import,
          :execute_v5_import)
    end

    result << MenuItem.new(t('menu.management.distributed_file'),
        :distributed_files, :view_distributed_files)
    result << MenuItem.new(t('menu.management.backup_and_restore'),
        :backup_and_restore,
        :backup_configuration)
    return result
  end

  def can_see_menu_item?(submenu_items)
    submenu_items.each do |each|
      return true if can?(each.privilege)
    end

    return false
  end

  def get_pki_subject_names(pki_top_CAs)
    subject_names = []
    pki_top_CAs.each do |top_ca|
      cert = CryptoUtils.readCertificate(top_ca.cert)
      subject_names << cert.getSubjectDN.getName
    end
    subject_names.join("; ")
  end

  def can_import_V5_data?
    return File.exists?("/usr/share/sdsb/bin/xtee55_clients_importer")
  end
end